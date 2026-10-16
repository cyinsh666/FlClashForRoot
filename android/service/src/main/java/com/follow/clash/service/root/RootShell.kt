package com.follow.clash.service.root

import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 每个代理会话独占一个 Root shell，stdin 关闭后由退出 trap 回收规则。 */
class RootShell : AutoCloseable {
    private val process = ProcessBuilder("su").redirectErrorStream(true).start()
    private val input = process.outputStream.bufferedWriter()
    private val output = LinkedBlockingQueue<String>()
    @Volatile private var closed = false

    init {
        thread(name = "flclash-root-output", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { output.offer(it) }
                }
            } finally {
                output.offer(END)
            }
        }
        try {
            check(run("id -u").lineSequence().lastOrNull() == "0") { "Root 授权被拒绝" }
        } catch (error: Exception) {
            abort()
            throw error
        }
    }

    /** 串行执行命令；超时和线程中断都会终止本 shell，防止误读上一条命令的输出。 */
    @Synchronized
    internal fun run(command: String, timeoutSeconds: Long = 25): String {
        check(!closed) { "Root 会话已关闭，请先清理残留规则" }
        val marker = "FLCLASH_${UUID.randomUUID().toString().replace("-", "")}:"
        try {
            input.write("$command\nprintf '\\n$marker%s\\n' \"\$?\"\n")
            input.flush()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            val lines = mutableListOf<String>()
            while (true) {
                val remaining = deadline - System.nanoTime()
                val line = if (remaining > 0) output.poll(remaining, TimeUnit.NANOSECONDS) else null
                if (line == null || line == END) {
                    abort()
                    error("Root 命令超时或会话退出")
                }
                if (line.startsWith(marker)) {
                    check(line.removePrefix(marker).toIntOrNull() == 0) {
                        lines.joinToString("\n").ifBlank { "Root 命令执行失败" }
                    }
                    return lines.joinToString("\n").trim()
                }
                if (lines.size < 4096) lines.add(line)
            }
        } catch (error: InterruptedException) {
            abort()
            Thread.currentThread().interrupt()
            throw error
        } catch (error: java.io.IOException) {
            abort()
            throw IllegalStateException("Root 命令通道已断开", error)
        }
    }

    /** 先释放输入触发清理；不能结束的子进程由超时/取消路径销毁，恢复记录仍保留。 */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
    }

    /** 取消授权或命令等待时停止进程；不清除磁盘上的恢复记录。 */
    private fun abort() {
        close()
        process.destroy()
    }

    companion object {
        private const val END = "\u0000ROOT_SESSION_ENDED"

        /** 支持 runInterruptible 中断；这里仅检查能力，不安装网络规则。 */
        fun check(): String = RootShell().use { shell ->
            shell.run(
                "test -c /dev/net/tun && command -v ip && command -v iptables && command -v ip6tables",
            )
            "已取得 Root，TUN、ip、iptables、ip6tables 可用；接管能力需要启动后确认"
        }

        /** 对传入 shell 的参数逐项引用，避免路径和接口名产生额外命令。 */
        internal fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
    }
}
