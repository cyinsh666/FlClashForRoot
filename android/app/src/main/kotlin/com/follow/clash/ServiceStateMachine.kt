package com.follow.clash

import com.follow.clash.common.RunIntentArbiter
import com.follow.clash.models.SharedState
import com.follow.clash.service.models.NotificationParams
import com.follow.clash.service.models.ServiceMode
import com.follow.clash.service.models.VpnOptions
import com.follow.clash.service.models.serviceMode
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

enum class RunState {
    STARTED,
    STARTING,
    STOPPING,
    STOPPED,
}

internal typealias RunRequest = RunIntentArbiter.Token

internal const val MISSING_CONFIG_MESSAGE = "No configuration found."
internal const val INVALID_CONFIG_MESSAGE = "Invalid configuration."
internal const val VPN_PERMISSION_MESSAGE = "VPN permission required."
internal const val START_FAILED_MESSAGE = "Failed to start service."

private enum class StartOutcome {
    STARTED,
    FAILED,
    SUPERSEDED,
}

/**
 * Callers request a transition; the newest request always wins. No step assigns [runState]:
 * [publish] derives it from the latest intent, the transition in flight and what the host really
 * runs, so no path can leave it claiming a service that is not there.
 */
internal class ServiceStateMachine(private val host: ServiceStateHost) {
    private val transitionLock = Mutex()
    private val startPreparationLock = Mutex()
    private val mutableRunState = MutableStateFlow(RunState.STOPPED)
    private val arbiter = RunIntentArbiter()

    @Volatile
    private var sharedState = SharedState()

    @Volatile
    private var pendingVpnPreparation: (() -> Unit)? = null

    // Root 授权不占用转换锁，停止操作通过取消任务中断 su 等待。
    @Volatile
    private var pendingRootPreparation: Deferred<String>? = null

    @Volatile
    private var transition: RunState? = null

    val runState = mutableRunState.asStateFlow()

    val isRunRequested: Boolean
        get() = arbiter.isRunningRequested

    suspend fun handleToggleAction() {
        if (isRunRequested) {
            handleStopAction()
        } else {
            handleStartAction()
        }
    }

    suspend fun awaitRunTime(): Long = transitionLock.withLock {
        val runtime = host.runtime
        runtime?.takeIf { isRunRequested && !it.cleanupPending }?.startedAtMillis ?: 0L
    }

    fun captureRequestToken(): RunRequest = arbiter.current()

    /**
     * Settles the state after the bound service was lost. [token] is the request that was current
     * when the loss was observed, so a start that raced ahead of this callback keeps its intent.
     */
    suspend fun handleServiceLost(token: RunRequest) {
        val dropped = transitionLock.withLock {
            if (host.runtime != null) {
                return@withLock false
            }
            arbiter.resetToStopped(token).also { publish() }
        }
        if (dropped) {
            host.notifyStopped()
        }
    }

    suspend fun handleStartAction() {
        if (isRunRequested) {
            return
        }
        val tile = host.tile()
        if (tile != null) {
            tile.handleStart()
            return
        }
        startFromPreferences()
    }

    /** Android Always-on 表示 VPN 意图，Root 模式不能把它解释成 Root 启动。 */
    suspend fun handleVpnStartAction() {
        val options = if (host.tile() == null && !isRunRequested) {
            host.loadSharedState().vpnOptions
        } else {
            sharedState.vpnOptions
        }
        if (host.runtime?.mode == ServiceMode.ROOT || options?.serviceMode == ServiceMode.ROOT) {
            host.stopUnmanagedVpn()
            return
        }
        handleStartAction()
    }

    suspend fun handleStopAction() {
        if (!isRunRequested) {
            return
        }
        host.showToast(sharedState.stopTip)
        requestStop().await()
        host.notifyStopped()
    }

    suspend fun handleVpnRevokeAction() {
        if (host.runtime?.mode != ServiceMode.VPN) {
            return
        }
        handleStopAction()
    }

    fun requestStart(): Deferred<Boolean> {
        if (isStartedAs(sharedState.vpnOptions)) {
            return CompletableDeferred(true)
        }
        val outcome = launchStart(createRequest(running = true))
        return host.scope.async { outcome.await() == StartOutcome.STARTED }
    }

    fun requestStop(): Deferred<Boolean> {
        val request = createRequest(running = false)
        return host.scope.async { stop(request) }
    }

    fun syncSharedState(state: SharedState) {
        sharedState = state
        applySharedState()
    }

    // No new request for a settled run: one refused at a prompt would take the service down.
    private fun isStartedAs(options: VpnOptions?): Boolean =
        runState.value == RunState.STARTED && host.runtime?.cleanupPending == false &&
            host.runtime?.mode == options?.serviceMode

    /** 异步故障只撤回创建该 Root 会话的意图，不能覆盖后来的启动操作。 */
    suspend fun handleRootFailure(token: RunRequest, message: String) {
        if (!arbiter.resetToStopped(token)) return
        host.log("RootProxy 异步故障=$message")
        host.showToast(message)
        reconcileStopped()
        host.notifyStopped()
    }

    /** 网络修复与启动、停止共用转换锁，运行中不允许移除正在使用的规则。 */
    suspend fun repairRoot(): String = transitionLock.withLock {
        if (isRunRequested) {
            return@withLock "请先停止代理，再恢复网络"
        }
        if (host.runtime != null && !stopLocked()) {
            return@withLock "Root 代理尚未清理完成，请重试停止后再恢复网络"
        }
        try {
            host.repairRoot()
            ""
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message ?: "恢复网络失败"
            host.log("RootProxy 恢复失败=$message")
            host.reportRootFailure(message)
            message
        }
    }

    // The intent is claimed before the Core is set up, so a second action that lands meanwhile
    // toggles this start off instead of racing a second setup against it.
    private suspend fun startFromPreferences() {
        val request = createRequest(running = true)
        sharedState = host.loadSharedState()
        if (sharedState.setupParams == null || sharedState.vpnOptions == null) {
            host.showToast(MISSING_CONFIG_MESSAGE)
            abandon(request)
            return
        }
        if (!setupCore()) {
            abandon(request)
        } else if (launchStart(request).await() == StartOutcome.FAILED &&
            sharedState.vpnOptions?.rootEnabled != true && host.runtime?.mode != ServiceMode.ROOT
        ) {
            host.showToast(START_FAILED_MESSAGE)
        }
    }

    private fun launchStart(request: RunRequest): Deferred<StartOutcome> {
        val result = CompletableDeferred<StartOutcome>()
        val launch: (Boolean) -> Unit = { permitted ->
            host.scope.launch {
                result.complete(if (permitted) start(request) else abandon(request))
            }
        }
        val app = host.app()
        if (app != null) {
            app.requestNotificationPermission { permitted ->
                if (permitted) {
                    app.requestLocalNetworkPermission { launch(true) }
                } else {
                    launch(false)
                }
            }
        } else {
            launch(true)
        }
        return result
    }

    private fun applySharedState() {
        host.setCrashlytics(sharedState.crashlytics)
        host.updateNotificationParams(notificationParams(sharedState))
    }

    private suspend fun setupCore(): Boolean {
        applySharedState()
        host.showToast(sharedState.startTip)
        // 后台快捷启动可能读取上一次保存的 YAML，Root DNS 覆盖必须同时交给 Go。
        val setupParams = Gson().toJsonTree(sharedState.setupParams).asJsonObject.apply {
            addProperty("root-proxy", sharedState.vpnOptions?.rootEnabled == true)
        }
        return host.quickSetup(
            initParams(host.homeDirPath, host.sdkInt),
            setupParams.toString(),
        ).fold(
            onSuccess = { message ->
                if (message.isEmpty()) {
                    true
                } else {
                    host.log("Unable to set up core: $message")
                    showConfigError(message)
                    false
                }
            },
            onFailure = { error ->
                host.log("Unable to set up core: $error")
                showConfigError(error.message)
                false
            },
        )
    }

    private fun showConfigError(message: String?) {
        host.showToast(message?.takeIf { it.isNotBlank() } ?: INVALID_CONFIG_MESSAGE)
    }

    private suspend fun start(request: RunRequest): StartOutcome {
        val started = try {
            startPreparationLock.withLock { runStart(request) }
        } catch (error: CancellationException) {
            if (isCurrent(request)) throw error
            false
        } catch (error: Exception) {
            host.log("Unable to process service start request: $error")
            if (isCurrent(request) &&
                (sharedState.vpnOptions?.rootEnabled == true ||
                    host.runtime?.mode == ServiceMode.ROOT || host.hasRootRecovery())
            ) {
                val message = error.message ?: START_FAILED_MESSAGE
                host.reportRootFailure(message)
                host.showToast(message)
            }
            false
        }
        return if (started) StartOutcome.STARTED else abandon(request)
    }

    private suspend fun abandon(request: RunRequest): StartOutcome {
        val dropped = arbiter.resetToStopped(request)
        reconcileStopped()
        if (!dropped) {
            return StartOutcome.SUPERSEDED
        }
        host.notifyStopped()
        return StartOutcome.FAILED
    }

    private suspend fun runStart(request: RunRequest): Boolean {
        if (!isCurrent(request)) {
            return false
        }
        val options = sharedState.vpnOptions ?: return false
        if (options.serviceMode == ServiceMode.ROOT) {
            if (!prepareRoot(request)) return false
        } else if (!prepareVpn(options)) {
            if (host.app() == null && isCurrent(request)) {
                host.showToast(VPN_PERMISSION_MESSAGE)
            }
            return false
        }
        return transitionLock.withLock {
            when {
                !isCurrent(request) -> false
                host.runtime?.mode == options.serviceMode &&
                    host.runtime?.cleanupPending == false -> true
                else -> transitioning(RunState.STARTING) {
                    host.startService(withLocalNetworkFallback(options)) && isCurrent(request)
                }
            }
        }
    }

    // system/mixed hand TCP to the kernel via the tun subnet, which Android 17 gates as local network.
    private fun withLocalNetworkFallback(options: VpnOptions): VpnOptions {
        if (options.serviceMode == ServiceMode.PROXY || options.stack !in KERNEL_TCP_STACKS ||
            host.isLocalNetworkPermissionGranted()
        ) {
            return options
        }
        host.showToast(sharedState.localNetworkTip)
        return options.copy(stack = FALLBACK_STACK)
    }

    // Flutter's startListener and the native quickSetup both open the Core's listeners before any
    // service exists to answer for them, and Flutter may be gone before it hears of the failure.
    private suspend fun reconcileStopped(): Unit = transitionLock.withLock {
        when {
            isRunRequested -> publish()
            host.runtime != null -> stopLocked()
            else -> {
                stopService()
                publish()
            }
        }
    }

    private suspend fun stop(request: RunRequest): Boolean = transitionLock.withLock {
        if (!isCurrent(request)) {
            return@withLock false
        }
        abandonVpnPreparation()
        val needsCleanup = host.runtime != null || host.hasRootRecovery()
        val stopped = !needsCleanup || stopLocked()
        stopped && isCurrent(request)
    }

    private suspend fun stopLocked() = transitioning(RunState.STOPPING) { stopService() }

    private suspend fun stopService(): Boolean {
        return try {
            host.stopService()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            host.log("Unable to stop service: $error")
            if (host.runtime?.mode == ServiceMode.ROOT || host.hasRootRecovery()) {
                val message = error.message ?: "Root 代理清理失败，请重试停止或恢复网络"
                host.reportRootFailure(message)
                host.showToast(message)
                false
            } else {
                true
            }
        }
    }

    private inline fun <T> transitioning(state: RunState, block: () -> T): T {
        transition = state
        publish()
        try {
            return block()
        } finally {
            transition = null
            publish()
        }
    }

    @Synchronized
    private fun publish() {
        mutableRunState.value = transition ?: when {
            host.runtime == null -> RunState.STOPPED
            host.runtime?.cleanupPending == true -> RunState.STOPPING
            isRunRequested -> RunState.STARTED
            else -> RunState.STOPPING
        }
    }

    private suspend fun prepareVpn(options: VpnOptions): Boolean {
        val app = host.app()
            ?: return !options.enable || host.isVpnPermissionGranted()
        return suspendCancellableCoroutine { continuation ->
            val callback: (Boolean) -> Unit = { granted ->
                pendingVpnPreparation = null
                if (continuation.isActive) {
                    continuation.resume(granted)
                }
            }
            pendingVpnPreparation = {
                app.cancelVpnPreparation(callback)
                callback(false)
            }
            continuation.invokeOnCancellation {
                pendingVpnPreparation = null
                app.cancelVpnPreparation(callback)
            }
            app.prepareVpn(options.enable, callback)
        }
    }

    private fun abandonVpnPreparation() {
        val abandon = pendingVpnPreparation ?: return
        pendingVpnPreparation = null
        abandon()
    }

    /** 授权任务可以被较新的意图取消，成功后仍要检查请求身份。 */
    private suspend fun prepareRoot(request: RunRequest): Boolean {
        val preparation = host.scope.async { host.checkRootPermission() }
        pendingRootPreparation = preparation
        return try {
            preparation.await()
            isCurrent(request)
        } finally {
            if (pendingRootPreparation === preparation) {
                pendingRootPreparation = null
            }
        }
    }

    /** 新意图先取得身份，再中断旧授权，旧任务不能回写当前运行状态。 */
    private fun createRequest(running: Boolean): RunRequest =
        arbiter.request(running).also {
            pendingRootPreparation?.cancel()
            publish()
        }

    private fun isCurrent(request: RunRequest): Boolean = arbiter.isCurrent(request)

    internal companion object {
        val KERNEL_TCP_STACKS = setOf("system", "mixed")
        const val FALLBACK_STACK = "gvisor"

        /**
         * The Core init payload. The key spelling is a cross-language contract with the Go wrapper,
         * not an implementation detail.
         */
        fun initParams(homeDirPath: String, sdkInt: Int): String = Gson().toJson(
            mapOf(
                "home-dir" to homeDirPath,
                "version" to sdkInt,
            ),
        )

        fun notificationParams(state: SharedState): NotificationParams = NotificationParams(
            title = state.currentProfileName,
            stopText = state.stopText,
            onlyStatisticsProxy = state.onlyStatisticsProxy,
            showStopAction = state.showStopAction,
        )
    }
}
