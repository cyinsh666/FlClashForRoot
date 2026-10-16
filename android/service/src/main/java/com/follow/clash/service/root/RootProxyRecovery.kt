package com.follow.clash.service.root

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Root 规则的恢复入口；普通 VPN 只有发现记录后才需要申请 Root。 */
object RootProxyRecovery {
    /** 只读文件状态，不启动 su。 */
    fun hasPending(context: Context): Boolean = recoveryFile(context).let { file ->
        file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()
    }

    /** 生命周期先确认旧服务已停止，再执行幂等清理；失败时保留恢复文件。 */
    fun repair(context: Context) {
        if (!hasPending(context)) return
        val file = recoveryFile(context)
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) {
            // 首次原子写入还没完成就退出时，只留下 .new；规则变更尚未开始。
            atomic.delete()
            check(!hasPending(context)) { "无法删除未完成的 Root 恢复记录" }
            return
        }
        // openRead 会先恢复一次未完成的原子写入。
        atomic.openRead().use { }
        RootShell().use { shell ->
            shell.run("sh ${RootShell.quote(recoveryFile(context).absolutePath)}", 45)
        }
        atomic.delete()
        check(!hasPending(context)) { "Root 规则已清理，但无法删除恢复记录" }
        RootProxyStatus.update("stopped")
    }

    /** 在任何持久系统变更前原子保存本次会话的撤销命令。 */
    internal fun save(context: Context, script: String) {
        val atomic = AtomicFile(recoveryFile(context))
        val output = atomic.startWrite()
        try {
            output.write((script + "\ncleanup_root\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    /** 清理命令确认成功后才能删除恢复记录。 */
    internal fun clear(context: Context) {
        AtomicFile(recoveryFile(context)).delete()
        check(!hasPending(context)) { "无法删除 Root 恢复记录" }
    }

    /** 恢复脚本只存放应用私有目录，不保存订阅或代理凭据。 */
    private fun recoveryFile(context: Context): File = File(context.filesDir, "root_proxy_recovery.sh")
}
