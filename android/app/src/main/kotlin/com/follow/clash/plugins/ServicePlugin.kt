package com.follow.clash.plugins

import android.content.Intent
import android.provider.Settings
import com.follow.clash.ServiceController
import com.follow.clash.ServiceState
import com.follow.clash.common.Components
import com.follow.clash.common.GlobalState
import com.follow.clash.models.SharedState
import com.follow.clash.service.models.ServiceMode
import com.follow.clash.service.root.RootProxyRecovery
import com.follow.clash.service.root.RootProxyStatus
import com.follow.clash.service.root.RootShell
import com.google.gson.Gson
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

class ServicePlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var scope: CoroutineScope
    private val gson = Gson()
    private var commandRevision = 0

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        channel = MethodChannel(binding.binaryMessenger, "${Components.PACKAGE_NAME}/service")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        scope.cancel()
        ServiceController.setEventListener(null)
    }

    override fun onMethodCall(call: MethodCall, rawResult: MethodChannel.Result) {
        // Most handlers below reply from a scope worker on Dispatchers.Default,
        // but a MethodChannel.Result has to be answered on the platform thread.
        // Wrapping once here covers every branch, including notImplemented.
        val result = MainThreadResult(rawResult)
        when (call.method) {
            "init" -> initialize(result)
            "shutdown" -> shutdown(result)
            "invokeMethod" -> invokeMethod(call, result)
            "getRunTime" -> getRunTime(result)
            "syncState" -> syncState(call, result)
            "start" -> start(call, result)
            "stop" -> stop(call, result)
            "rootStop" -> stopRoot(call, result)
            "rootStatus" -> rootStatus(result)
            "rootCheck" -> rootCheck(result)
            "rootRepair" -> rootRepair(result)
            "rootHotspotSettings" -> rootHotspotSettings(result)
            else -> result.notImplemented()
        }
    }

    private fun initialize(result: MethodChannel.Result) {
        ServiceController.setEventListener(::sendEvent)
            .onSuccess { result.success("") }
            .onFailure { error -> result.success(error.message.orEmpty()) }
    }

    private fun shutdown(result: MethodChannel.Result) {
        ServiceController.setEventListener(null)
        result.success(true)
    }

    private fun invokeMethod(call: MethodCall, result: MethodChannel.Result) {
        val data = call.arguments as? String
        if (data == null) {
            result.error("INVALID_ARGUMENT", "Method call payload must be a string", null)
            return
        }
        // Inline rather than a coroutine per call, which could swap two: watchRoute
        // keeps the latest intent by the order calls reach the Core
        // (dispatchMethodCall in core/method.go), and this only hands data over.
        ServiceController.invokeMethod(data) { response ->
            result.success(response)
        }.onFailure { error ->
            result.error("CORE_ERROR", error.message, null)
        }
    }

    private fun getRunTime(result: MethodChannel.Result) {
        scope.launch {
            result.success(ServiceState.awaitRunTime())
        }
    }

    private fun syncState(call: MethodCall, result: MethodChannel.Result) {
        val data = call.arguments as? String
        val state = runCatching {
            gson.fromJson(data, SharedState::class.java)
        }.getOrNull()
        if (state == null) {
            result.success("Invalid shared state")
            return
        }
        scope.launch {
            ServiceState.syncSharedState(state)
            result.success("")
        }
    }

    private fun start(call: MethodCall, result: MethodChannel.Result) {
        commandRevision = call.arguments as? Int ?: commandRevision
        ServiceState.requestStart()
        result.success(true)
    }

    private fun stop(call: MethodCall, result: MethodChannel.Result) {
        commandRevision = call.arguments as? Int ?: commandRevision
        ServiceState.requestStop()
        result.success(true)
    }

    /** Root 停止需等规则撤销后再关闭 Core，普通 VPN 保留原有乐观命令语义。 */
    private fun stopRoot(call: MethodCall, result: MethodChannel.Result) {
        commandRevision = call.arguments as? Int ?: commandRevision
        val operation = ServiceState.requestStop()
        scope.launch {
            result.success(operation.await())
        }
    }

    /** 状态查询不请求 Root，恢复标记来自本应用持久化的恢复记录。 */
    private fun rootStatus(result: MethodChannel.Result) {
        scope.launch {
            val status = RootProxyStatus.snapshot().toMutableMap()
            val runtime = ServiceController.runtime
            val rootRunning = runtime?.mode == ServiceMode.ROOT
            val phase = status["phase"] as? String ?: ""
            val healthyPhases = setOf("active", "waitingHotspot", "waitingHotspotRestart")
            val normalSession = rootRunning && runtime?.cleanupPending == false &&
                phase in healthyPhases
            val startingSession = ServiceState.isRunRequested && phase == "starting"
            status["rootRunning"] = rootRunning
            status["needsRepair"] = RootProxyRecovery.hasPending(GlobalState.application) &&
                !normalSession && !startingSession
            result.success(status)
        }
    }

    /** 手动检测只读取 Root 能力，线程中断由 RootShell 转为进程取消。 */
    private fun rootCheck(result: MethodChannel.Result) {
        scope.launch {
            try {
                val summary = runInterruptible(Dispatchers.IO) { RootShell.check() }
                result.success(summary)
            } catch (error: Exception) {
                result.error("ROOT_CHECK_FAILED", error.message ?: "Root 能力检测失败", null)
            }
        }
    }

    /** 通过统一状态机修复，避免与正在收敛的服务转换互相覆盖。 */
    private fun rootRepair(result: MethodChannel.Result) {
        scope.launch {
            result.success(ServiceState.repairRoot())
        }
    }

    /** 优先打开热点设置，设备没有该入口时打开无线网络设置。 */
    private fun rootHotspotSettings(result: MethodChannel.Result) {
        val application = GlobalState.application
        val opened = listOf("android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)
            .any { action ->
                runCatching {
                    application.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            }
        result.success(opened)
    }

    // Read on the platform thread, where start and stop arrive: revision and intent are one instant.
    fun notifyStopped() {
        scope.launch(Dispatchers.Main) {
            if (!ServiceState.isRunRequested) {
                channel.invokeMethod("stopped", commandRevision)
            }
        }
    }

    private fun sendEvent(value: String?) {
        scope.launch(Dispatchers.Main) {
            channel.invokeMethod("event", value)
        }
    }
}
