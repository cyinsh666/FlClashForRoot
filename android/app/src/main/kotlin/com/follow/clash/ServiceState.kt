package com.follow.clash

import com.follow.clash.models.SharedState
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.Deferred

object ServiceState {
    private val machine = ServiceStateMachine(AndroidServiceStateHost)

    val runState = machine.runState

    val isRunRequested: Boolean
        get() = machine.isRunRequested

    fun attachFlutterEngine(engine: FlutterEngine) =
        AndroidServiceStateHost.attachFlutterEngine(engine)

    fun detachFlutterEngine(engine: FlutterEngine) =
        AndroidServiceStateHost.detachFlutterEngine(engine)

    suspend fun handleToggleAction() = machine.handleToggleAction()

    suspend fun handleStartAction() = machine.handleStartAction()

    /** 系统始终开启 VPN 的入口与用户快捷启动保持明确区分。 */
    suspend fun handleVpnStartAction() = machine.handleVpnStartAction()

    suspend fun handleStopAction() = machine.handleStopAction()

    suspend fun handleVpnRevokeAction() = machine.handleVpnRevokeAction()

    suspend fun awaitRunTime(): Long = machine.awaitRunTime()

    fun requestStart(): Deferred<Boolean> = machine.requestStart()

    fun requestStop(): Deferred<Boolean> = machine.requestStop()

    fun syncSharedState(state: SharedState) = machine.syncSharedState(state)

    internal fun captureRequestToken(): RunRequest = machine.captureRequestToken()

    internal suspend fun handleServiceLost(token: RunRequest) = machine.handleServiceLost(token)

    /** Root 服务故障必须交给同一个意图仲裁器处理。 */
    internal suspend fun handleRootFailure(token: RunRequest, message: String) =
        machine.handleRootFailure(token, message)

    /** 恢复系统网络前，由状态机确认服务已经停止。 */
    suspend fun repairRoot(): String = machine.repairRoot()
}
