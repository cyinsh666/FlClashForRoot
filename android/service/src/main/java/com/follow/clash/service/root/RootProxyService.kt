package com.follow.clash.service.root

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import com.follow.clash.core.Core
import com.follow.clash.service.ManagedService
import com.follow.clash.service.ServiceConfig
import com.follow.clash.service.models.tunOptions
import com.follow.clash.service.modules.ServiceModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 独立 Root 模式不创建 VpnService，也不改变普通 VPN 服务的启动路径。 */
class RootProxyService : Service(), ManagedService {
    private val binder = LocalBinder()
    private val modules = ServiceModules(this)
    // 清理失败时保留路由会话和 TUN，让控制器能重试停止。
    private var routes: RootRouteSession? = null
    private var scope: CoroutineScope? = null
    private var tunRunning = false
    private var started = false
    // 异步故障后的控制器收敛停止仍保留错误，直到用户重新启动。
    private var failureMessage = ""

    /** 服务生命周期由 App 层唯一的 ServiceController 管理。 */
    inner class LocalBinder : Binder() {
        val service: RootProxyService get() = this@RootProxyService
    }

    /** 仅暴露给应用内部的绑定服务。 */
    override fun onBind(intent: Intent): IBinder = binder

    /** 恢复旧会话后先建立数据面，再独立安装本机和热点接管规则。 */
    @Synchronized
    override fun start() {
        if (started) return
        val options = requireNotNull(ServiceConfig.vpnOptions) { "缺少 Root 代理配置" }
        check(options.rootLocal || options.rootHotspot) { "请至少选择本机流量或热点流量" }
        started = true
        failureMessage = ""
        // 故障回调绑定本次启动，旧观察器不能读取后来一次启动的回调。
        val failureCallback = ServiceConfig.rootFailure
        RootProxyStatus.update("starting")
        try {
            modules.start()
            RootProxyRecovery.repair(this)
            val shell = RootShell()
            val tun = "fcr${Process.myPid()}"
            val session = try {
                RootRouteSession(this, shell, tun, options)
            } catch (error: Exception) {
                shell.close()
                throw error
            }
            routes = session
            val reader = RootNetworkReader(this, shell, options.rootHotspot, options.rootInterface, tun)
            val network = reader.read()
            RootTunBridge.create(this, shell, tun).use { descriptor ->
                session.initialize(network)
                // 新版 Core 接管原始 FD；成功与失败都会释放，不再由 Kotlin 重复关闭。
                tunRunning = true
                check(
                    Core.startTun(
                        fd = descriptor.detachFd(),
                        protect = { true },
                        resolveUid = { _, _, _ -> -1 },
                        resolvePackage = { uid -> packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty() },
                        stack = options.stack,
                        address = session.tunnelAddress,
                        dns = "0.0.0.0",
                        options = JSONObject(options.tunOptions).put("root-proxy", true).toString(),
                    ),
                ) { "Core 无法启动 Root TUN，请检查 DNS 和当前配置" }
            }
            publish(network, session.apply(network))
            observe(reader, network, session, failureCallback)
        } catch (error: Exception) {
            val cleanup = runCatching { stop() }.exceptionOrNull()
            val message = if (cleanup == null) error.message.orEmpty() else
                "${error.message.orEmpty()}；Root 清理失败：${cleanup.message.orEmpty()}"
            RootProxyStatus.update(if (cleanup == null) "failed" else "cleanupFailed", message)
            throw IllegalStateException(message, error)
        }
    }

    /** 接口变化立即处理，完整规则健康检查和失败热点重试最多每 30 秒一次。 */
    private fun observe(
        reader: RootNetworkReader,
        initial: RootNetwork,
        session: RootRouteSession,
        failureCallback: ((String) -> Unit)?,
    ) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { worker ->
            worker.launch {
                var network = initial
                var checks = 0
                while (isActive) {
                    delay(3_000)
                    synchronized(this@RootProxyService) {
                        if (routes !== session || !started) return@synchronized
                        try {
                            val next = reader.read()
                            checks++
                            val due = checks >= 10
                            if (due) checks = 0
                            val retryHotspot = due && next.downstream.isNotEmpty() && !session.result.hotspotActive
                            if (next != network || retryHotspot || (due && !session.isInstalled())) {
                                publish(next, session.apply(next))
                                network = next
                            }
                        } catch (error: Exception) {
                            failureMessage = error.message.orEmpty()
                            val cleanup = runCatching { stop() }.exceptionOrNull()
                            val message = if (cleanup == null) error.message.orEmpty() else
                                "${error.message.orEmpty()}；Root 清理失败：${cleanup.message.orEmpty()}"
                            RootProxyStatus.update(if (cleanup == null) "failed" else "cleanupFailed", message)
                            // 仅异步故障通知控制器一次；同步 start 的错误由调用方处理。
                            failureCallback?.invoke(message)
                        }
                    }
                }
            }
        }
    }

    /** 两个接管开关独立展示，等待热点时仍可报告本机处于工作状态。 */
    private fun publish(network: RootNetwork, result: RootRouteResult) {
        RootProxyStatus.update(result.phase, result.error, network, result.localActive, result.hotspotActive)
    }

    /** 先撤销网络规则；失败时保留 TUN、通知和会话，允许再次停止并阻止切换 VPN。 */
    @Synchronized
    override fun stop() {
        if (!started) return
        scope?.cancel()
        scope = null
        try {
            routes?.close()
        } catch (error: Exception) {
            RootProxyStatus.update("cleanupFailed", error.message.orEmpty())
            throw error
        }
        routes = null
        if (tunRunning) {
            Core.stopTun()
            tunRunning = false
        }
        modules.stop()
        started = false
        RootProxyStatus.update(if (failureMessage.isEmpty()) "stopped" else "failed", failureMessage)
        stopSelf()
    }

    /** 系统销毁尽力使用相同清理路径，无法完成的会话由持久化脚本恢复。 */
    override fun onDestroy() {
        try {
            runCatching { stop() }.onFailure {
                RootProxyStatus.record("阶段=服务销毁清理 结果=失败 原因=${it.message.orEmpty()}")
            }
        } finally {
            super.onDestroy()
        }
    }

    /** 与普通服务一致响应内存告警，不重新建立透明代理。 */
    override fun onLowMemory() {
        Core.forceGC()
        super.onLowMemory()
    }
}
