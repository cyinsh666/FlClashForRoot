package com.follow.clash.service.root

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.follow.clash.common.AccessControlMode
import com.follow.clash.service.models.AccessControlProps

/** 本机接管范围沿用应用选择；自身和 Root 系统服务绕过，保留内核出站及系统 DNS。 */
internal object RootUidPolicy {
    /** 把应用 UID 合并为少量连续区间，供 ip rule 和 DNS/IPv6 owner 规则共同使用。 */
    fun ranges(context: Context, control: AccessControlProps): List<LongRange> {
        val ownUid = Process.myUid().toLong()
        val selected = when {
            !control.enable -> emptySet()
            control.mode == AccessControlMode.ACCEPT_SELECTED -> resolve(context, control.acceptList)
            else -> resolve(context, control.rejectList)
        }
        if (control.enable && control.mode == AccessControlMode.ACCEPT_SELECTED) {
            return merge(selected.filter { it != ownUid && it != 0L }.sorted())
        }
        // netd 等 Root 系统服务保留原网络，避免其 DNS 套接字被本机路由再次接管。
        val excluded = (selected + ownUid + 0L).sorted()
        val ranges = mutableListOf<LongRange>()
        var start = 0L
        for (uid in excluded) {
            if (start < uid) ranges += start until uid
            start = uid + 1
        }
        if (start <= MAX_UID) ranges += start..MAX_UID
        return ranges
    }

    /** 已卸载应用跳过，与普通 VPN 页的应用列表行为一致。 */
    @Suppress("DEPRECATION")
    private fun resolve(context: Context, packages: List<String>): Set<Long> = packages.mapNotNull { name ->
        try {
            context.packageManager.getApplicationInfo(name, 0).uid.toLong()
        } catch (_: PackageManager.NameNotFoundException) {
            RootProxyStatus.record("阶段=应用分流 应用=$name 结果=应用已卸载")
            null
        }
    }.toSet()

    /** 连续的选择项只生成一条范围规则。 */
    private fun merge(values: List<Long>): List<LongRange> {
        val result = mutableListOf<LongRange>()
        for (value in values) {
            val previous = result.lastOrNull()
            if (previous != null && previous.last + 1 == value) {
                result[result.lastIndex] = previous.first..value
            } else {
                result += value..value
            }
        }
        return result
    }

    private const val MAX_UID = 4294967294L
}
