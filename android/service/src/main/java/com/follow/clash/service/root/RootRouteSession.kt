package com.follow.clash.service.root

import android.content.Context
import android.os.Process
import com.follow.clash.service.models.VpnOptions
import com.follow.clash.service.models.getIpv4RouteAddress
import com.follow.clash.service.models.tunMtu

/** 只管理本会话的链、路由表和系统设置，不清空 Android 自己的防火墙。 */
internal class RootRouteSession(
    private val context: Context,
    private val shell: RootShell,
    private val tun: String,
    private val options: VpnOptions,
) : AutoCloseable {
    // 自有链和独占路由表都带会话所有权，恢复不会清空系统规则。
    private val chain = "FCR_${Process.myPid()}"
    // RA 保护独立于普通路由重装，防止客户端在空窗中重新收到 IPv6 DNS。
    private val raChain = "${chain}_RA"
    private val ingress = RootIngressGuard(shell)
    // 应用路由、DNS 和 IPv6 阻断共用 UID 区间，Root 系统 DNS 在防火墙中单独处理。
    private val localRanges = if (options.rootLocal) RootUidPolicy.ranges(context, options.accessControlProps) else emptyList()
    private var table = 0
    private var priority = 0
    private var restore = emptyList<String>()
    private var canSetHardwareOffload = false
    private var canSetBpfOffload = false
    private var initialized = false
    private var installed: RootNetwork? = null
    private lateinit var tunnel: RootIpv4
    lateinit var tunnelAddress: String
        private set
    private lateinit var tunnelDns: String
    var result = RootRouteResult()
        private set

    /** 配置虚拟接口，但在内核确认就绪之前不接管流量。 */
    fun initialize(network: RootNetwork) {
        tunnel = selectTunnel(network.connected)
        tunnelAddress = "${RootIpv4.address(tunnel.start + 1)}/30"
        tunnelDns = RootIpv4.address(tunnel.start + 2)
        shell.run("command -v ip && command -v iptables && command -v ip6tables")
        // Android 内置 ip 不支持 -N；本会话使用未注册别名的数值表。
        val rules = shell.run("ip -4 rule show")
        table = (18000..18100).firstOrNull { candidate ->
            !rules.contains(Regex("lookup $candidate( |$)", RegexOption.MULTILINE)) &&
                shell.run("ip -4 route show table $candidate 2>/dev/null || true").isBlank()
        } ?: error("没有可用的 Root 路由表")
        priority = (9000..9900 step 10).firstOrNull { candidate ->
            (candidate..candidate + 3).none { rules.contains(Regex("^$it:", RegexOption.MULTILINE)) }
        } ?: error("没有可用的 Root 策略路由优先级")
        restore = createRestoration()
        initialized = true
        persistRecovery()
        shell.run("ip -4 addr add $tunnelAddress dev $tun && ip link set dev $tun mtu ${options.tunMtu} up")
        shell.run("echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter && echo 0 > /proc/sys/net/ipv4/conf/$tun/rp_filter")
        if (options.rootHotspot) {
            if (canSetHardwareOffload) configureOffload("settings put global tether_offload_disabled 1")
            if (canSetBpfOffload) configureOffload("device_config put connectivity override_tether_enable_bpf_offload false")
        }
        RootProxyStatus.record("阶段=隧道配置 接口=$tun 地址=$tunnelAddress DNS=$tunnelDns 路由表=$table 优先级=$priority 本机UID区间=${localRanges.size}")
    }

    /** 热点失败时仍安装本机规则；只有拓扑变化或低频检查失败才重新执行。 */
    fun apply(network: RootNetwork): RootRouteResult {
        check(network.connected.map { RootIpv4.parse(it) }.none(tunnel::overlaps)) { "新网络与 Root TUN 网段冲突，请重启代理重新选择网段" }
        var hotspotReady = options.rootHotspot && network.downstream.isNotEmpty()
        var reason = ""
        if (hotspotReady && network.offloadIssue.isNotEmpty()) {
            hotspotReady = false
            reason = network.offloadIssue
        }
        if (hotspotReady && network.hardwareOffload) {
            hotspotReady = false
            reason = "热点硬件加速仍在运行，请关闭并重新打开系统热点"
        }
        if (hotspotReady && shell.run("cat /proc/sys/net/ipv4/ip_forward") != "1") {
            hotspotReady = false
            reason = "系统热点尚未开启 IPv4 转发"
        }
        try {
            val guarded = if (hotspotReady && network.bpfOffload) network.downstream.keys else emptySet()
            ingress.ensure(guarded, ::persistRecovery)
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            hotspotReady = false
            reason = error.message ?: "热点加速规则尚未适配"
            RootProxyStatus.record("阶段=热点接管检查 结果=未接管 原因=$reason")
        }
        ensureRouterAdvertisementGuard(if (hotspotReady) network.downstream.keys else emptySet())
        val commands = mutableListOf<String>()
        commands += "failed=0; clear_routes; [ \"\$failed\" = 0 ]"
        commands += routeCommands(network, hotspotReady)
        commands += firewallCommands(network, hotspotReady)
        commands += "root_route_snapshot=\"\$(ip -4 route show table $table)\""
        try {
            shell.run(commands.joinToString(" &&\n"), 45)
        } catch (error: Exception) {
            runCatching { shell.run("failed=0; clear_routes; [ \"\$failed\" = 0 ]", 45) }
            throw error
        }
        installed = network
        result = RootRouteResult(
            localActive = options.rootLocal && localRanges.isNotEmpty(),
            hotspotActive = hotspotReady,
            phase = when {
                options.rootHotspot && network.downstream.isEmpty() -> "waitingHotspot"
                options.rootHotspot && !hotspotReady -> "waitingHotspotRestart"
                else -> "active"
            },
            error = reason,
        )
        return result
    }

    /** 检查会话入口和策略路由是否被系统网络重配删除；不为检查而重装规则。 */
    fun isInstalled(): Boolean {
        val network = installed ?: return false
        val checks = mutableListOf(
            "iptables -w 5 -t nat -C POSTROUTING -j $chain",
            "iptables -w 5 -t filter -C FORWARD -j $chain",
            "ip6tables -w 5 -t filter -C FORWARD -j $chain",
            "ip -4 rule show | grep -q '^${priority + 3}:.*lookup $table'",
            "[ \"\$(ip -4 route show table $table)\" = \"\$root_route_snapshot\" ]",
        )
        if (result.localActive) {
            checks += "iptables -w 5 -t nat -C OUTPUT -j ${chain}_LOCAL"
            checks += "ip6tables -w 5 -t filter -C OUTPUT -j ${chain}_LOCAL"
            checks += "iptables -w 5 -t nat -C ${chain}_LOCAL -m owner --uid-owner 0 -j ${chain}_DNS"
            for (protocol in listOf("udp", "tcp")) {
                checks += "ip6tables -w 5 -t filter -C ${chain}_LOCAL ! -o lo -p $protocol --dport 53 -m owner --uid-owner 0 -j REJECT"
            }
            for (range in localRanges) {
                checks += "ip -4 rule show | grep -q '^${priority + 2}:.*uidrange ${range.first}-${range.last}.*lookup $table'"
            }
        }
        if (result.hotspotActive) {
            checks += "ip -4 rule show | grep -q '^${priority + 1}:.*iif $tun.*lookup $table'"
            for (name in network.downstream.keys) {
                checks += "ip -4 rule show | grep -q '^$priority:.*iif $name.*lookup $table'"
                checks += "iptables -w 5 -t nat -C PREROUTING -i $name -j ${chain}_DNS"
                checks += "ip6tables -w 5 -t filter -C ${routerAdvertisementRule(name)}"
            }
            checks += "ip6tables -w 5 -t filter -C OUTPUT -j $raChain"
        }
        checks += ingress.checks()
        return shell.run("if ${checks.joinToString(" && ")}; then printf installed; else printf missing; fi") == "installed"
    }

    /** 新接口的 RA 保护先就绪，再撤旧接口；相同规则只核对，不删除重装。 */
    private fun ensureRouterAdvertisementGuard(interfaces: Set<String>) {
        if (interfaces.isEmpty()) {
            shell.run("failed=0; cleanup_ra; [ \"\$failed\" = 0 ]")
        } else {
            val commands = mutableListOf<String>()
            commands += "if ! ip6tables -w 5 -t filter -S $raChain >/dev/null 2>&1; then ip6tables -w 5 -t filter -N $raChain; fi"
            for (name in interfaces) {
                val rule = routerAdvertisementRule(name)
                commands += "if ! ip6tables -w 5 -t filter -C $rule >/dev/null 2>&1; then ip6tables -w 5 -t filter -A $rule; fi"
            }
            commands += "if ! ip6tables -w 5 -t filter -C OUTPUT -j $raChain >/dev/null 2>&1; then ip6tables -w 5 -t filter -I OUTPUT 1 -j $raChain; fi"
            // 只删除本会话独占链中的旧接口规则，先前部分成功的变更也能收敛。
            val retained = interfaces.joinToString("|") { "*\" -o $it \"*" }
            commands += "rules=\"\$(ip6tables -w 5 -t filter -S $raChain)\" && " +
                "printf '%s\\n' \"\$rules\" | while IFS= read -r rule; do case \"\$rule\" in " +
                "$retained) ;; \"-A $raChain \"*) " +
                "ip6tables -w 5 -t filter \$(echo \"\$rule\" | sed 's/^-A /-D /') || exit 1;; esac; done"
            shell.run(commands.joinToString(" &&\n"))
        }
    }

    /** 精确匹配 AP 出口的 RA，不影响其它接口的 IPv6 通告。 */
    private fun routerAdvertisementRule(name: String): String =
        "$raChain -o $name -p ipv6-icmp --icmpv6-type router-advertisement -j DROP"

    /** routeAddress 为空表示全部 IPv4；非空但没有 IPv4 时保持该地址族绕过。 */
    private fun routeCommands(network: RootNetwork, hotspotReady: Boolean): List<String> {
        val commands = mutableListOf<String>()
        val captured = if (options.routeAddress.isEmpty()) listOf("0.0.0.0/0") else {
            options.getIpv4RouteAddress().map { RootIpv4.from(it.address.address, it.prefixLength).cidr }.distinct()
        }
        val bypassed = (network.connected + SPECIAL_NETWORKS).map { RootIpv4.parse(it) }
        for (cidr in captured) {
            val capture = RootIpv4.parse(cidr)
            // 更细的配置路由也不能重新接管真实 LAN，否则 /25 会盖过 /24 的 throw。
            if (bypassed.any { it.start <= capture.start && capture.end <= it.end }) continue
            commands += "ip -4 route replace $cidr dev $tun table $table"
        }
        // 只绕过真实已连接网段，不再把整个 RFC1918 都视为局域网。
        for (cidr in network.connected + SPECIAL_NETWORKS) {
            commands += "ip -4 route replace throw $cidr table $table"
        }
        commands += "ip -4 route replace ${tunnel.cidr} dev $tun table $table"
        commands += "ip -4 route replace $tunnelDns/32 dev $tun table $table"
        if (hotspotReady) {
            for ((name, cidr) in network.downstream) {
                // 回程必须覆盖同网段的 throw，才能将 TUN 回复送回热点客户端。
                commands += "ip -4 route replace $cidr dev $name table $table"
                commands += "ip -4 rule add pref $priority iif $name lookup $table"
            }
            commands += "ip -4 rule add pref ${priority + 1} iif $tun lookup $table"
        }
        for (range in localRanges) {
            commands += "ip -4 rule add pref ${priority + 2} iif lo uidrange ${range.first}-${range.last} lookup $table"
        }
        // system/mixed 栈的隧道地址回复不受 App UID 绕过策略影响。
        commands += "ip -4 rule add pref ${priority + 3} to ${tunnel.cidr} lookup $table"
        return commands
    }

    /** 应用 DNS、NAT、IPv6 沿用本机/热点选择，Root 系统服务只额外接管 DNS。 */
    private fun firewallCommands(network: RootNetwork, hotspotReady: Boolean): List<String> {
        val commands = mutableListOf<String>()
        commands += "iptables -w 5 -t nat -N $chain"
        commands += "iptables -w 5 -t filter -N $chain"
        commands += "ip6tables -w 5 -t filter -N $chain"
        commands += "iptables -w 5 -t nat -N ${chain}_DNS"
        commands += "iptables -w 5 -t nat -N ${chain}_LOCAL"
        commands += "ip6tables -w 5 -t filter -N ${chain}_LOCAL"
        for (protocol in listOf("udp", "tcp")) {
            commands += "iptables -w 5 -t nat -A ${chain}_DNS -p $protocol --dport 53 -j DNAT --to-destination $tunnelDns:53"
        }
        if (hotspotReady) {
            for ((name, cidr) in network.downstream) {
                commands += "iptables -w 5 -t nat -A $chain -o $tun -s $cidr -j MASQUERADE"
                commands += "iptables -w 5 -t filter -A $chain -i $name -o $tun -j ACCEPT"
                commands += "iptables -w 5 -t filter -A $chain -i $tun -o $name -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT"
                commands += "ip6tables -w 5 -t filter -A $chain -i $name -j REJECT"
                commands += "iptables -w 5 -t nat -I PREROUTING 1 -i $name -j ${chain}_DNS"
            }
        }
        if (localRanges.isNotEmpty()) {
            // Root 通用路由仍直连；仅把系统 DNS 发包交给现有隧道 DNS，避免使用原网络解析结果。
            commands += "iptables -w 5 -t nat -A ${chain}_LOCAL -m owner --uid-owner 0 -j ${chain}_DNS"
            for (protocol in listOf("udp", "tcp")) {
                // 当前隧道只接管 IPv4；只拒绝 Root 的 IPv6 DNS，保留其它系统 IPv6 通信。
                commands += "ip6tables -w 5 -t filter -A ${chain}_LOCAL ! -o lo -p $protocol --dport 53 -m owner --uid-owner 0 -j REJECT"
            }
            for (range in localRanges) {
                val owner = "-m owner --uid-owner ${range.first}-${range.last}"
                commands += "iptables -w 5 -t nat -A ${chain}_LOCAL $owner -j ${chain}_DNS"
                commands += "ip6tables -w 5 -t filter -A ${chain}_LOCAL ! -o lo $owner -j REJECT"
            }
            commands += "iptables -w 5 -t nat -I OUTPUT 1 -j ${chain}_LOCAL"
        }
        if (localRanges.isNotEmpty()) {
            commands += "ip6tables -w 5 -t filter -I OUTPUT 1 -j ${chain}_LOCAL"
        }
        commands += "iptables -w 5 -t nat -I POSTROUTING 1 -j $chain"
        commands += "iptables -w 5 -t filter -I FORWARD 1 -j $chain"
        commands += "ip6tables -w 5 -t filter -I FORWARD 1 -j $chain"
        return commands
    }

    /** 先记录系统原值；恢复时只覆盖仍等于本次写入值的设置。 */
    private fun createRestoration(): List<String> {
        val commands = mutableListOf<String>()
        for (path in listOf("/proc/sys/net/ipv4/conf/all/rp_filter", "/proc/sys/net/ipv4/conf/$tun/rp_filter")) {
            val old = shell.run("cat ${RootShell.quote(path)}")
            require(old in listOf("0", "1", "2")) { "无法读取反向路径过滤设置" }
            commands += "if [ -f '$path' ] && [ \"\$(cat '$path')\" = 0 ]; then echo $old > '$path' || failed=1; fi"
        }
        if (options.rootHotspot) {
            try {
                commands += restoreSetting("settings", "global", "tether_offload_disabled", "1")
                canSetHardwareOffload = true
                if (shell.run("command -v device_config >/dev/null && printf available || true") == "available") {
                    commands += restoreSetting("device_config", "connectivity", "override_tether_enable_bpf_offload", "false")
                    canSetBpfOffload = true
                }
            } catch (error: Exception) {
                if (error is InterruptedException) throw error
                RootProxyStatus.record("阶段=读取热点加速配置 结果=不可用 原因=${error.message.orEmpty()}")
            }
        }
        return commands
    }

    /** 部分 ROM 不允许修改加速设置，实际能否接管由后续硬件/BPF 检查决定。 */
    private fun configureOffload(command: String) {
        try {
            shell.run(command)
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            RootProxyStatus.record("阶段=关闭热点加速配置 结果=不可用 原因=${error.message.orEmpty()}")
        }
    }

    /** 系统值可能由用户或其它工具更改，停止时不覆盖这些后续变更。 */
    private fun restoreSetting(tool: String, namespace: String, key: String, installed: String): String {
        val old = shell.run("$tool get $namespace $key")
        val command = if (old == "null") "$tool delete $namespace $key" else "$tool put $namespace $key ${RootShell.quote(old)}"
        return "if [ \"\$($tool get $namespace $key)\" = '$installed' ]; then $command || failed=1; fi"
    }

    /** 初始化就记录 RA 链名；每次 TC 所有权变化前同步恢复脚本和退出 trap。 */
    private fun persistRecovery() {
        val script = "clear_routes() {\n${clearCommands().joinToString("\n")}\n}\n" +
            "cleanup_ra() {\n${routerAdvertisementCleanupCommands().joinToString("\n")}\n}\n" +
            "cleanup_ingress() {\n${ingress.cleanupCommands()}\n}\n" +
            "cleanup_root() {\nfailed=0\nclear_routes\ncleanup_ra\ncleanup_ingress\n${restore.joinToString("\n")}\n[ \"\$failed\" = 0 ]\n}\n"
        RootProxyRecovery.save(context, script)
        shell.run(script + "trap 'cleanup_root' EXIT HUP TERM\ntrue")
    }

    /** 退出或热点不可接管时清理独立 RA 入口和链，失败保留恢复记录。 */
    private fun routerAdvertisementCleanupCommands(): List<String> = listOf(
        "rules=\"\$(ip6tables -w 5 -t filter -S OUTPUT)\" || failed=1; " +
            "printf '%s\\n' \"\$rules\" | while IFS= read -r rule; do case \"\$rule\" in " +
            "*\"-j $raChain\") ip6tables -w 5 -t filter \$(echo \"\$rule\" | sed 's/^-A /-D /') || exit 1;; esac; done || failed=1",
        "if ip6tables -w 5 -t filter -S $raChain >/dev/null 2>&1; then ip6tables -w 5 -t filter -F $raChain || failed=1; fi",
        "if ip6tables -w 5 -t filter -S $raChain >/dev/null 2>&1; then ip6tables -w 5 -t filter -X $raChain || failed=1; fi",
    )

    /** 只重装普通链和本会话路由表，独立 RA 链始终保留。 */
    private fun clearCommands(): List<String> {
        val commands = mutableListOf<String>()
        for (tool in listOf("iptables", "ip6tables")) {
            for (kind in if (tool == "iptables") listOf("nat", "filter") else listOf("filter")) {
                val builtins = if (kind == "nat") listOf("PREROUTING", "OUTPUT", "POSTROUTING") else listOf("FORWARD", "OUTPUT")
                for (builtin in builtins) {
                    commands += "rules=\"\$($tool -w 5 -t $kind -S $builtin)\" || failed=1; " +
                        "printf '%s\\n' \"\$rules\" | while IFS= read -r rule; do case \"\$rule\" in " +
                        "*\"-j $chain\"|*\"-j ${chain}_DNS\"|*\"-j ${chain}_LOCAL\") " +
                        "$tool -w 5 -t $kind \$(echo \"\$rule\" | sed 's/^-A /-D /') || exit 1;; esac; done || failed=1"
                }
                for (owned in listOf(chain, "${chain}_LOCAL", "${chain}_DNS")) {
                    commands += "if $tool -w 5 -t $kind -S $owned >/dev/null 2>&1; then $tool -w 5 -t $kind -F $owned || failed=1; fi"
                }
                for (owned in listOf(chain, "${chain}_LOCAL", "${chain}_DNS")) {
                    commands += "if $tool -w 5 -t $kind -S $owned >/dev/null 2>&1; then $tool -w 5 -t $kind -X $owned || failed=1; fi"
                }
            }
        }
        for (value in priority..priority + 3) {
            // 先检查读取状态，不能把 ip 自身失败当作没有剩余规则。
            commands += "rules=\"\$(ip -4 rule show)\" || failed=1; " +
                "while printf '%s\\n' \"\$rules\" | grep -q '^$value:.*lookup $table'; do " +
                "ip -4 rule del pref $value lookup $table || { failed=1; break; }; " +
                "rules=\"\$(ip -4 rule show)\" || { failed=1; break; }; done"
        }
        commands += "ip -4 route flush table $table 2>/dev/null || true"
        // 空表单独查询可能返回 ENOENT；读取所有表成功后再确认自有数值表已消失。
        commands += "routes=\"\$(ip -4 route show table all)\" || failed=1"
        commands += "if printf '%s\\n' \"\$routes\" | grep -qE '(^| )table $table( |\$)'; then failed=1; fi"
        return commands
    }

    /** 首选与新版 VPN 相同的基准网段，冲突时再使用私网 /30。 */
    private fun selectTunnel(connected: Set<String>): RootIpv4 {
        val occupied = connected.map { RootIpv4.parse(it) }
        val candidates = sequence {
            yield(RootIpv4.parse("198.18.0.0/30"))
            for (second in 31 downTo 16) for (third in 255 downTo 0) {
                yield(RootIpv4.parse("172.$second.$third.0/30"))
            }
            for (second in 255 downTo 0) yield(RootIpv4.parse("10.$second.255.0/30"))
        }
        return candidates.firstOrNull { candidate -> occupied.none(candidate::overlaps) }
            ?: error("未找到与当前网络不冲突的 Root TUN 网段")
    }

    /** 清理失败保留会话供下一次停止重试，恢复文件也不会丢失。 */
    override fun close() {
        if (!initialized) {
            shell.close()
            return
        }
        try {
            shell.run("cleanup_root && trap - EXIT HUP TERM", 45)
            RootProxyRecovery.clear(context)
        } catch (error: Exception) {
            // shell 被取消或断开后，使用持久化脚本开新会话再清理一次。
            RootProxyRecovery.repair(context)
        }
        initialized = false
        shell.close()
    }

    companion object {
        private val SPECIAL_NETWORKS = setOf("0.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16", "224.0.0.0/4", "240.0.0.0/4")
    }
}

/** 本机和热点独立记录，热点等待时本机仍能继续代理。 */
internal data class RootRouteResult(
    val phase: String = "stopped",
    val error: String = "",
    val localActive: Boolean = false,
    val hotspotActive: Boolean = false,
)
