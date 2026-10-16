package com.follow.clash.service.root

import android.util.Log
import com.follow.clash.common.GlobalState

/** 页面读取真实接管结果，不把配置开关当作运行成功。 */
object RootProxyStatus {
    private var phase = "stopped"
    private var error = ""
    private var upstream = ""
    private var downstream = emptyList<String>()
    private var localActive = false
    private var hotspotActive = false
    private val logs = ArrayDeque<String>()

    /** 仅在状态变化时打印，轮询不重复制造日志。 */
    @Synchronized
    internal fun update(
        next: String,
        message: String = "",
        network: RootNetwork? = null,
        local: Boolean = false,
        hotspot: Boolean = false,
    ) {
        val nextDownstream = network?.downstream?.map { (name, cidr) -> "$name $cidr" }.orEmpty()
        val changed = phase != next || error != message || upstream != network?.upstream.orEmpty() ||
            downstream != nextDownstream || localActive != local || hotspotActive != hotspot
        phase = next
        error = message
        upstream = network?.upstream.orEmpty()
        downstream = nextDownstream
        localActive = local
        hotspotActive = hotspot
        if (changed) {
            record(
                "阶段=$next 本机接管=$local 热点接管=$hotspot 上游=$upstream 热点=$downstream 原因=$message",
                important = next == "failed" || next == "cleanupFailed",
            )
        }
    }

    /** 保留有限的状态与错误记录，供透明代理详情页展示。 */
    @Synchronized
    internal fun record(message: String, important: Boolean = false) {
        val line = "RootProxy $message"
        logs.addLast(line)
        while (logs.size > 100) logs.removeFirst()
        if (important) Log.e("FlClash", line) else GlobalState.log(line)
    }

    /** Root 授权和启动准备失败也交给页面展示，由生命周期层调用。 */
    fun reportFailure(message: String) = update("failed", message)

    /** 返回快照，调用方不能修改服务内部状态。 */
    @Synchronized
    fun snapshot(): Map<String, Any> = mapOf(
        "phase" to phase,
        "error" to error,
        "upstream" to upstream,
        "downstream" to downstream.toList(),
        "logs" to logs.toList(),
        "localActive" to localActive,
        "hotspotActive" to hotspotActive,
    )
}
