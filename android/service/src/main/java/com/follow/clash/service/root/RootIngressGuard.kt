package com.follow.clash.service.root

import android.os.Process

/** 对已识别的 Android tether BPF，提前结束分类并交回常规 IP 路由。 */
internal class RootIngressGuard(private val shell: RootShell) {
    private val handle = "800::${(Process.myPid() % 4095 + 1).toString(16)}"
    private val flowId = "fc01:1"
    private val owned = linkedSetOf<String>()

    /** 不覆盖未知 TC 规则；撤销命令先持久化，再安装自己的节点。 */
    fun ensure(interfaces: Set<String>, persist: () -> Unit) {
        if (interfaces.isEmpty() && owned.isEmpty()) return
        shell.run("command -v tc >/dev/null")
        for (name in owned.toList().filter { it !in interfaces }) {
            shell.run("failed=0; ${cleanupCommand(name)}; [ \"\$failed\" = 0 ]")
            owned.remove(name)
            persist()
        }
        for (name in interfaces) {
            val slot = shell.run("tc filter show dev $name ingress pref 1")
            if (name in owned && isOurRule(slot)) continue
            check(slot.isBlank()) { "热点接口 $name 的 TC 优先级 1 已被占用" }
            val filters = shell.run("tc filter show dev $name ingress")
            check(isSupportedFilters(filters)) { "热点接口 $name 存在尚未适配的 TC/BPF 规则" }
            owned.add(name)
            persist()
            shell.run("tc filter add dev $name ingress protocol all pref 1 handle $handle u32 match u32 0 0 flowid $flowId")
            check(isOurRule(shell.run("tc filter show dev $name ingress pref 1"))) { "热点接口 $name 的 BPF 绕过规则未生效" }
        }
    }

    /** 只绕过已识别的 tether 程序；Oplus 的系统分流必须明确指向 IFB，不能误过其它厂商规则。 */
    private fun isSupportedFilters(value: String): Boolean {
        // tc 先输出分类器摘要，再输出带 handle 的规则；action 必须归属于其前一条详细规则。
        val blocks = value.trim().split(Regex("\n(?=filter )"))
        val headers = blocks.map { it.lineSequence().first() }
        if (headers.isEmpty() || headers.any { !it.startsWith("filter ") || !it.contains(" bpf ") }) return false
        val details = blocks.filter { it.lineSequence().first().contains("handle ") }
        return details.isNotEmpty() && details.all { block ->
            val header = block.lineSequence().first()
            val actions = block.lineSequence().map(String::trim).filter { it.startsWith("action ") }.toList()
            when {
                header.contains(Regex("(?:^|\\s)prog_offload_schedcls_tether_upstream[46]_")) -> actions.isEmpty()
                header.contains(Regex("(?:^|\\s)prog_oplus-netd_schedcls_ingress_data_redirect:\\[\\*fsobj\\](?:\\s|$)")) ->
                    actions.size == 1 && actions.single().matches(
                        Regex("action order 1:\\s+mirred \\(Egress Redirect to device ifb[0-9]+\\) stolen"),
                    )
                else -> false
            }
        }
    }

    /** 只把本次会话拥有的节点加入统一恢复脚本。 */
    fun cleanupCommands(): String = owned.joinToString("\n", transform = ::cleanupCommand).ifBlank { ":" }

    /** 低频健康检查复用整段 shell，避免每个接口额外启动进程。 */
    fun checks(): List<String> = owned.map { name ->
        "tc filter show dev $name ingress pref 1 | grep -F 'fh $handle ' | grep -qF 'flowid $flowId '"
    }

    /** 使用句柄和 flowid 双重判断，避免删除系统重建后的同名节点。 */
    private fun isOurRule(value: String): Boolean = value.lineSequence().any {
        it.contains("fh $handle ") && it.contains("flowid $flowId ")
    }

    /** 不删除系统 clsact 和 tether BPF，只撤销本次会话的终结节点。 */
    private fun cleanupCommand(name: String): String =
        "if ip link show dev $name >/dev/null 2>&1; then " +
            "guard=\"\$(tc filter show dev $name ingress pref 1)\" || failed=1; " +
            "if printf '%s\\n' \"\$guard\" | grep -F 'fh $handle ' | grep -qF 'flowid $flowId '; then " +
            "tc filter del dev $name ingress protocol all pref 1 handle $handle u32 || failed=1; fi; fi"
}
