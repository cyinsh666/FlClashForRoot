package com.follow.clash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.follow.clash.common.GlobalState
import com.follow.clash.common.intent
import com.follow.clash.core.Core
import com.follow.clash.service.ManagedService
import com.follow.clash.service.ProxyService
import com.follow.clash.service.ServiceConfig
import com.follow.clash.service.VpnService
import com.follow.clash.service.models.ServiceMode
import com.follow.clash.service.models.VpnOptions
import com.follow.clash.service.models.serviceMode
import com.follow.clash.service.root.RootProxyRecovery
import com.follow.clash.service.root.RootProxyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

object ServiceController {
    private val lock = Mutex()
    private var binding: ManagedServiceBinding? = null

    @Volatile
    internal var runtime: ServiceRuntime? = null
        private set

    private fun clearBinding() {
        binding?.unbind()
        binding = null
    }

    // The Core listens exactly while a run exists, so every path that ends one closes it.
    private fun release() {
        clearBinding()
        ServiceConfig.rootFailure = null
        runtime = null
        Core.stopListener()
    }

    fun invokeMethod(data: String, callback: (ByteArray) -> Unit): Result<Unit> = runCatching {
        Core.invokeMethod(data) { result ->
            callback(result ?: ByteArray(0))
        }
    }

    suspend fun quickSetup(
        initParams: String,
        setupParams: String,
    ): Result<String> = runCatching {
        suspendCancellableCoroutine { continuation ->
            Core.quickSetup(initParams, setupParams) { result ->
                continuation.resume(result.orEmpty())
            }
        }
    }

    fun setEventListener(callback: ((String?) -> Unit)?): Result<Unit> = runCatching {
        Core.updateEventListener(callback)
    }

    /** 只绑定一种服务，旧 Root 规则清理成功后才能交给新模式接管。 */
    suspend fun start(options: VpnOptions): Boolean = lock.withLock {
        ServiceConfig.updateVpnOptions(options)
        val nextIntent = when (options.serviceMode) {
            ServiceMode.VPN -> VpnService::class.intent
            ServiceMode.PROXY -> ProxyService::class.intent
            ServiceMode.ROOT -> RootProxyService::class.intent
        }

        if (binding?.component != nextIntent.component || runtime?.cleanupPending == true) {
            if (binding != null) {
                tearDownServices()
            }
            // 进程重启后恢复文件仍可能存在；普通 VPN 仅在确有残留时才需要 Root。
            if (options.serviceMode != ServiceMode.ROOT) {
                repairPendingRoot()
            }
            val requestToken = ServiceState.captureRequestToken()
            lateinit var nextBinding: ManagedServiceBinding
            nextBinding = ManagedServiceBinding(nextIntent) { message ->
                handleServiceDisconnected(nextBinding, message, requestToken)
            }
            binding = nextBinding
            if (options.serviceMode == ServiceMode.ROOT) {
                ServiceConfig.rootFailure = { message ->
                    handleRootFailure(nextBinding, requestToken, message)
                }
            } else {
                ServiceConfig.rootFailure = null
            }
            nextBinding.bind().onFailure { error ->
                GlobalState.log("Unable to bind background service: $error")
                release()
                if (options.serviceMode == ServiceMode.ROOT) throw error
                return@withLock false
            }
        }

        val currentBinding = binding ?: return@withLock false
        val result = currentBinding.useService { service -> service.start() }
        if (result.isFailure) {
            GlobalState.log("Unable to start background service: ${result.exceptionOrNull()}")
            val cleanup = currentBinding.stopIfConnected()
            cleanup.onFailure { error ->
                GlobalState.log("Unable to clean up failed background service start: $error")
            }
            if (options.serviceMode == ServiceMode.ROOT && cleanup.isFailure) {
                throw retainRootCleanupFailure(cleanup.exceptionOrNull())
            }
            release()
            if (options.serviceMode == ServiceMode.ROOT) {
                throw requireNotNull(result.exceptionOrNull())
            }
            return@withLock false
        }

        Core.startListener()
        runtime = ServiceRuntime(
            mode = options.serviceMode,
            startedAtMillis = runtime?.startedAtMillis ?: System.currentTimeMillis(),
        )
        true
    }

    /** 撤销实际服务和遗留路由后，才能释放 Core 的监听资源。 */
    suspend fun stop() = lock.withLock {
        tearDownServices()
        // 绑定丢失后仍须等恢复记录清理完成，Root 停止屏障才能放行关闭 Core。
        repairPendingRoot()
        release()
    }

    /** 只关闭系统额外创建的空 VPN，不触碰当前绑定的服务。 */
    suspend fun stopUnmanagedVpn(): Unit = lock.withLock {
        if (runtime?.mode != ServiceMode.VPN &&
            binding?.component != VpnService::class.intent.component
        ) {
            GlobalState.application.stopService(VpnService::class.intent)
        }
    }

    /** Root 清理失败需要保留重试端点，其它服务延续原有停止语义。 */
    private suspend fun tearDownServices() {
        val rootBinding = binding?.component == RootProxyService::class.intent.component
        val stopResult = if (rootBinding) {
            binding?.stopIfConnected()
        } else {
            binding?.useService { service -> service.stop() }
        }
        stopResult?.onFailure { error ->
            GlobalState.log("Unable to stop background service: $error")
        }
        if (rootBinding && stopResult?.isFailure == true) {
            throw retainRootCleanupFailure(stopResult.exceptionOrNull())
        }
        clearBinding()
        stopServices()
    }

    // A service the system started itself — always-on VPN, or the sticky restart
    // after the process was killed — outlives every binding this process holds, so
    // unbinding alone leaves the tunnel up while the app reports it stopped.
    private fun stopServices() {
        listOf(
            VpnService::class.intent,
            ProxyService::class.intent,
            RootProxyService::class.intent,
        ).forEach { intent ->
            runCatching { GlobalState.application.stopService(intent) }
                .onFailure { error ->
                    GlobalState.log("Unable to stop ${intent.component?.className}: $error")
                }
        }
    }

    /** 系统规则清理失败时保留绑定和资源归属，不能继续启动另一种模式。 */
    private fun retainRootCleanupFailure(error: Throwable?): IllegalStateException {
        runtime = ServiceRuntime(
            mode = ServiceMode.ROOT,
            startedAtMillis = runtime?.startedAtMillis ?: System.currentTimeMillis(),
            cleanupPending = true,
        )
        val message = error?.message ?: "Root 代理规则清理失败，请重试停止或恢复网络"
        GlobalState.log("RootProxy 清理失败=$message")
        return IllegalStateException(message, error)
    }

    /** 即使原进程的绑定已经丢失，恢复失败仍须保留 Root 资源归属。 */
    private suspend fun repairPendingRoot() {
        if (!RootProxyRecovery.hasPending(GlobalState.application)) return
        try {
            runInterruptible(Dispatchers.IO) {
                RootProxyRecovery.repair(GlobalState.application)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw retainRootCleanupFailure(error)
        }
    }

    /** 先确认失败仍属于当前绑定，再让状态机撤回对应的启动意图。 */
    private fun handleRootFailure(
        failedBinding: ManagedServiceBinding,
        token: RunRequest,
        message: String,
    ) {
        GlobalState.launch {
            val isCurrentBinding = lock.withLock { binding === failedBinding }
            if (isCurrentBinding) {
                ServiceState.handleRootFailure(token, message)
            }
        }
    }

    /** Root 异常断绑先清理系统接管规则，失败时保留资源归属。 */
    private fun handleServiceDisconnected(
        disconnectedBinding: ManagedServiceBinding,
        message: String,
        rootRequestToken: RunRequest,
    ) {
        val isRoot = disconnectedBinding.component == RootProxyService::class.intent.component
        val token = if (isRoot) rootRequestToken else ServiceState.captureRequestToken()
        GlobalState.launch {
            var rootFailure: String? = null
            val wasCurrent = lock.withLock {
                if (binding !== disconnectedBinding) {
                    return@withLock false
                }
                GlobalState.log("Background service disconnected: $message")
                if (isRoot) {
                    try {
                        disconnectedBinding.stopIfConnected().getOrThrow()
                        repairPendingRoot()
                        stopServices()
                    } catch (error: Exception) {
                        rootFailure = retainRootCleanupFailure(error).message
                        return@withLock true
                    }
                }
                release()
                true
            }
            if (wasCurrent) {
                val failure = rootFailure
                if (failure == null) {
                    ServiceState.handleServiceLost(token)
                } else {
                    ServiceState.handleRootFailure(token, failure)
                }
            }
        }
    }
}

private class ManagedServiceBinding(
    private val intent: Intent,
    private val onDisconnected: (String) -> Unit,
) : ServiceConnection {
    val component: ComponentName?
        get() = intent.component

    private val serviceState = MutableStateFlow<Result<ManagedService>?>(null)

    // 同进程 Binder 失联后仍保留清理端点，Root 规则撤销失败时允许再次停止。
    @Volatile
    private var lastConnectedService: ManagedService? = null

    @Volatile
    private var isBound = false

    suspend fun bind(): Result<Unit> = runCatching {
        withContext(Dispatchers.Main.immediate) {
            serviceState.value = null
            isBound = GlobalState.application.bindService(
                intent,
                this@ManagedServiceBinding,
                Context.BIND_AUTO_CREATE,
            )
            check(isBound) { "bindService() failed" }
        }
    }

    suspend fun <R> useService(
        connectionTimeoutMillis: Long = 5_000,
        block: suspend (ManagedService) -> R,
    ): Result<R> = runCatching {
        val service = withTimeout(connectionTimeoutMillis) {
            serviceState.filterNotNull().first().getOrThrow()
        }
        withContext(Dispatchers.Default) {
            block(service)
        }
    }

    /** 绑定失联时仍可调用同进程清理端点，不必等待一次不可能完成的重连。 */
    suspend fun stopIfConnected(): Result<Unit> = runCatching {
        val service = lastConnectedService ?: return@runCatching
        withContext(Dispatchers.Default) {
            service.stop()
        }
    }

    fun unbind() {
        serviceState.value = null
        lastConnectedService = null
        if (!isBound) return
        isBound = false
        Handler(Looper.getMainLooper()).post {
            runCatching {
                GlobalState.application.unbindService(this)
            }.onFailure { error ->
                GlobalState.log("Unable to unbind background service: $error")
            }
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        runCatching {
            when (binder) {
                is VpnService.LocalBinder -> binder.service
                is ProxyService.LocalBinder -> binder.service
                is RootProxyService.LocalBinder -> binder.service
                null -> error("Binder is empty")
                else -> error("Unsupported service binder: ${binder.javaClass.name}")
            }
        }.onSuccess { service ->
            lastConnectedService = service
            serviceState.value = Result.success(service)
        }.onFailure { error ->
            disconnect(error.message.orEmpty())
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        disconnect("Service disconnected")
    }

    override fun onBindingDied(name: ComponentName?) {
        disconnect("Service binding died")
    }

    override fun onNullBinding(name: ComponentName?) {
        disconnect("Service returned an empty binder")
    }

    private fun disconnect(message: String) {
        serviceState.value = Result.failure(IllegalStateException(message))
        onDisconnected(message)
    }
}
