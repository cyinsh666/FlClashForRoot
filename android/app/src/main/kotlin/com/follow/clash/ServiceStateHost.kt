package com.follow.clash

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import com.follow.clash.common.GlobalState
import com.follow.clash.models.SharedState
import com.follow.clash.plugins.AppPlugin
import com.follow.clash.plugins.ServicePlugin
import com.follow.clash.plugins.TilePlugin
import com.follow.clash.service.ServiceConfig
import com.follow.clash.service.models.NotificationParams
import com.follow.clash.service.models.ServiceMode
import com.follow.clash.service.models.VpnOptions
import com.follow.clash.service.root.RootProxyRecovery
import com.follow.clash.service.root.RootProxyStatus
import com.follow.clash.service.root.RootShell
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal data class ServiceRuntime(
    val mode: ServiceMode,
    val startedAtMillis: Long,
    val cleanupPending: Boolean = false,
) {
    /** 保留 VPN 运行状态的只读语义，服务身份统一以 mode 为准。 */
    val vpn: Boolean
        get() = mode == ServiceMode.VPN

    /** 旧调用仍可表达原有的两种服务，新调用必须指定明确的模式。 */
    constructor(vpn: Boolean, startedAtMillis: Long) : this(
        if (vpn) ServiceMode.VPN else ServiceMode.PROXY,
        startedAtMillis,
    )
}

/**
 * The machine owns the arbitration and the transitions; this is only the part that cannot run on a
 * plain JVM, so unit tests can drive the state machine with an in-memory implementation.
 */
internal interface ServiceStateHost {
    val scope: CoroutineScope
    val runtime: ServiceRuntime?
    val homeDirPath: String
    val sdkInt: Int

    fun log(message: String)

    fun showToast(message: String)

    fun setCrashlytics(enabled: Boolean)

    fun updateNotificationParams(params: NotificationParams)

    fun loadSharedState(): SharedState

    fun isVpnPermissionGranted(): Boolean

    fun isLocalNetworkPermissionGranted(): Boolean

    fun tile(): TileGateway?

    fun app(): AppGateway?

    fun notifyStopped()

    suspend fun quickSetup(initParams: String, setupParams: String): Result<String>

    suspend fun startService(options: VpnOptions): Boolean

    suspend fun stopService()

    /** 系统 Always-on 拉起的空 VPN 服务不能影响正在运行的 Root 服务。 */
    suspend fun stopUnmanagedVpn() = Unit

    /** Root 授权是独立的准备步骤，停止意图可以取消这一步。 */
    suspend fun checkRootPermission(): String = error("Root proxy is unavailable")

    /** 仅由状态机确认停止后执行，不允许页面直接修改系统路由。 */
    suspend fun repairRoot() = Unit

    /** 原生冷启动失败时，页面也能读取原因。 */
    fun reportRootFailure(message: String) = Unit

    /** 读取恢复标记不执行 su，也不改变网络。 */
    fun hasRootRecovery(): Boolean = false
}

internal fun interface TileGateway {
    fun handleStart()
}

internal interface AppGateway {
    fun requestNotificationPermission(callback: (Boolean) -> Unit)

    fun requestLocalNetworkPermission(callback: (Boolean) -> Unit)

    fun prepareVpn(enable: Boolean, callback: (Boolean) -> Unit)

    fun cancelVpnPreparation(callback: (Boolean) -> Unit)
}

internal object AndroidServiceStateHost : ServiceStateHost {
    @Volatile
    private var flutterEngine: FlutterEngine? = null

    override val scope: CoroutineScope
        get() = GlobalState

    override val runtime: ServiceRuntime?
        get() = ServiceController.runtime

    override val homeDirPath: String
        get() = GlobalState.application.filesDir.path

    override val sdkInt: Int
        get() = android.os.Build.VERSION.SDK_INT

    fun attachFlutterEngine(engine: FlutterEngine) {
        flutterEngine = engine
    }

    fun detachFlutterEngine(engine: FlutterEngine) {
        if (flutterEngine === engine) {
            flutterEngine = null
        }
    }

    override fun log(message: String) = GlobalState.log(message)

    override fun showToast(message: String) = GlobalState.application.showToast(message)

    override fun setCrashlytics(enabled: Boolean) = GlobalState.setCrashlytics(enabled)

    override fun updateNotificationParams(params: NotificationParams) =
        ServiceConfig.updateNotificationParams(params)

    override fun loadSharedState(): SharedState = GlobalState.application.sharedState

    override fun isVpnPermissionGranted(): Boolean =
        VpnService.prepare(GlobalState.application) == null

    override fun isLocalNetworkPermissionGranted(): Boolean =
        sdkInt < Build.VERSION_CODES.CINNAMON_BUN ||
            ContextCompat.checkSelfPermission(
                GlobalState.application,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED

    override fun tile(): TileGateway? = flutterEngine?.plugin<TilePlugin>()?.let { plugin ->
        TileGateway(plugin::handleStart)
    }

    override fun app(): AppGateway? = flutterEngine?.plugin<AppPlugin>()?.let { plugin ->
        object : AppGateway {
            override fun requestNotificationPermission(callback: (Boolean) -> Unit) =
                plugin.requestNotificationPermission(callback)

            override fun requestLocalNetworkPermission(callback: (Boolean) -> Unit) =
                plugin.requestLocalNetworkPermission(callback)

            override fun prepareVpn(enable: Boolean, callback: (Boolean) -> Unit) =
                plugin.prepareVpn(enable, callback)

            override fun cancelVpnPreparation(callback: (Boolean) -> Unit) =
                plugin.cancelVpnPreparation(callback)
        }
    }

    override fun notifyStopped() {
        flutterEngine?.plugin<ServicePlugin>()?.notifyStopped()
    }

    override suspend fun quickSetup(initParams: String, setupParams: String): Result<String> =
        ServiceController.quickSetup(initParams, setupParams)

    override suspend fun startService(options: VpnOptions): Boolean =
        ServiceController.start(options)

    override suspend fun stopService() = ServiceController.stop()

    /** ServiceController 仍是绑定与实际运行状态的唯一所有者。 */
    override suspend fun stopUnmanagedVpn() = ServiceController.stopUnmanagedVpn()

    /** 中断会传递给 RootShell，RootShell 负责销毁正在等待的 su 进程。 */
    override suspend fun checkRootPermission(): String = runInterruptible(Dispatchers.IO) {
        RootShell.check()
    }

    /** 修复操作与服务切换共用状态机的转换锁。 */
    override suspend fun repairRoot() = runInterruptible(Dispatchers.IO) {
        RootProxyRecovery.repair(GlobalState.application)
    }

    /** 清理失败的详细阶段由服务保留，其他准备错误记录为启动失败。 */
    override fun reportRootFailure(message: String) {
        if (RootProxyStatus.snapshot()["phase"] != "cleanupFailed") {
            RootProxyStatus.reportFailure(message)
        }
    }

    /** 用于识别上次进程退出留下的 Root 资源。 */
    override fun hasRootRecovery(): Boolean = RootProxyRecovery.hasPending(GlobalState.application)
}
