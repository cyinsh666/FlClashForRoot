package com.follow.clash.service.root

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.core.content.getSystemService
import java.net.Inet4Address
import java.net.NetworkInterface

/** 所有路由决策使用同一份拓扑，避免接口地址和热点状态来自不同轮次。 */
internal data class RootNetwork(
    val upstream: String,
    val downstream: Map<String, String>,
    val connected: Set<String>,
    val hardwareOffload: Boolean,
    val bpfOffload: Boolean,
    // 空字符串表示已确认加速状态；未知时热点不能报告接管成功。
    val offloadIssue: String,
)

/** 先读取本机接口；拓扑稳定时最多每 30 秒查询一次 Root dumpsys。 */
internal class RootNetworkReader(
    private val context: Context,
    private val shell: RootShell,
    private val hotspot: Boolean,
    private val selected: String,
    private val tun: String,
) {
    private var previousInterfaces = ""
    private var previousDumpAt = 0L
    private var tetherDump = ""
    private var tetherQueryIssue = ""

    /** 不假设热点固定叫 wlan0；手动接口也必须是当前上游以外的有效接口。 */
    fun read(): RootNetwork {
        val connectivity = requireNotNull(context.getSystemService<ConnectivityManager>())
        val active = connectivity.activeNetwork
        check(connectivity.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
            "请先关闭系统 VPN，再使用 Root 透明代理"
        }
        val upstream = connectivity.getLinkProperties(active)?.interfaceName.orEmpty()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && it.name != tun }
        val networks = interfaces.associate { item ->
            item.name to item.interfaceAddresses.filter { it.address is Inet4Address }
                .map { RootIpv4.from(it.address.address, it.networkPrefixLength.toInt()).cidr }
                .filter { RootIpv4.parse(it).prefix > 0 }
        }
        val candidates = interfaces.filter { !it.isPointToPoint && it.name != upstream }
        val signature = upstream + networks.toSortedMap().toString()
        if (hotspot && (signature != previousInterfaces || SystemClock.elapsedRealtime() - previousDumpAt > 30_000)) {
            try {
                tetherDump = shell.run("dumpsys tethering")
                if (tetherDump.contains("Can't find service")) tetherDump = shell.run("dumpsys connectivity")
                tetherQueryIssue = ""
            } catch (error: Exception) {
                if (error is InterruptedException) throw error
                tetherDump = ""
                tetherQueryIssue = "无法查询系统热点加速状态：${error.message.orEmpty()}"
                RootProxyStatus.record("阶段=热点状态查询 结果=未确认 原因=$tetherQueryIssue")
            }
            previousInterfaces = signature
            previousDumpAt = SystemClock.elapsedRealtime()
        }
        val names = when {
            !hotspot -> emptyList()
            selected.isNotBlank() -> {
                check(selected.matches(INTERFACE_NAME)) { "热点接口名称无效" }
                check(selected != upstream) { "热点接口不能填写当前上游接口 $upstream" }
                listOf(selected)
            }
            else -> candidates.filter { item ->
                item.name.matches(WIFI_AP_NAME) && tetherDump.lineSequence().any { line ->
                    line.contains(Regex("(?<![a-zA-Z0-9_])${Regex.escape(item.name)}(?![a-zA-Z0-9_])")) &&
                        line.contains("TetheredState")
                }
            }.map { it.name }
        }
        val downstream = names.mapNotNull { name ->
            if (candidates.none { it.name == name }) return@mapNotNull null
            val cidr = networks[name]?.firstOrNull() ?: return@mapNotNull null
            name to cidr
        }.toMap()
        // 没有实际下行接口时只等待热点，不要求 ROM 提供加速状态。
        val offload = if (downstream.isEmpty()) RootOffloadState() else readOffload()
        return RootNetwork(
            upstream = upstream,
            downstream = downstream,
            connected = networks.values.flatten().toSet(),
            hardwareOffload = offload.hardware,
            bpfOffload = offload.bpf,
            offloadIssue = offload.issue,
        )
    }

    /** 只接受明确的 AOSP 状态行，缺失或矛盾都不能推断为加速关闭。 */
    private fun readOffload(): RootOffloadState {
        if (tetherQueryIssue.isNotEmpty()) return RootOffloadState(issue = tetherQueryIssue)
        // 新版系统在硬件加速段明确输出 Offload disabled，只在该段读取此状态。
        var hardwareSection = false
        val hardware = tetherDump.lineSequence().mapNotNull { line ->
            when (line.trim()) {
                "Hardware offload:" -> {
                    hardwareSection = true
                    null
                }
                "BPF offload:" -> {
                    hardwareSection = false
                    null
                }
                "Offload HALs started" -> true
                "Offload HALs not started" -> false
                "Offload disabled" -> if (hardwareSection) false else null
                else -> null
            }
        }.toSet()
        if (hardware.size != 1) {
            return RootOffloadState(issue = "无法确认热点硬件加速状态，暂未接管热点流量")
        }
        val hardwareActive = hardware.single()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return RootOffloadState(hardware = hardwareActive)
        }
        val bpf = tetherDump.lineSequence().mapNotNull { line ->
            BPF_STATE.matchEntire(line.trim())?.groupValues?.get(1)?.toBooleanStrictOrNull()
        }.toSet()
        if (bpf.size != 1) {
            return RootOffloadState(hardware = hardwareActive, issue = "无法确认热点 BPF 加速状态，暂未接管热点流量")
        }
        return RootOffloadState(hardware = hardwareActive, bpf = bpf.single())
    }

    companion object {
        private val INTERFACE_NAME = Regex("[a-zA-Z0-9_.-]{1,15}")
        private val WIFI_AP_NAME = Regex("(?:wlan|ap|swlan|softap|br|wificond)[a-zA-Z0-9_.-]*")
        // 使用原始字符串匹配系统状态行的空白，避免双重转义改变正则含义。
        private val BPF_STATE = Regex("""(?:mIsBpfEnabled|isBpfEnabled)\s*[:=]\s*(true|false)""")
    }
}

/** 明确区分“已关闭加速”和“尚未确认”，未知值不能启用热点接管。 */
private data class RootOffloadState(
    val hardware: Boolean = false,
    val bpf: Boolean = false,
    val issue: String = "",
)
