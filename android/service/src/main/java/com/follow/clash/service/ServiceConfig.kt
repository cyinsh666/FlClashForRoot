package com.follow.clash.service

import com.follow.clash.service.models.NotificationParams
import com.follow.clash.service.models.VpnOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServiceConfig {
    // 回调由当前绑定会话设置，旧服务的异步失败不能停止新的运行会话。
    @Volatile
    var rootFailure: ((String) -> Unit)? = null

    @Volatile
    private var currentVpnOptions: VpnOptions? = null
    private val mutableNotificationParams = MutableStateFlow(NotificationParams())

    val vpnOptions: VpnOptions?
        get() = currentVpnOptions

    val notificationParams = mutableNotificationParams.asStateFlow()

    fun updateVpnOptions(options: VpnOptions) {
        currentVpnOptions = options
    }

    fun updateNotificationParams(params: NotificationParams) {
        mutableNotificationParams.value = params
    }
}
