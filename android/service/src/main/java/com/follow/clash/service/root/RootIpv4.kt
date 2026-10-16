package com.follow.clash.service.root

import java.net.Inet4Address
import java.net.InetAddress

/** 用明确的无符号区间处理网段重叠，避免 Int 左移溢出。 */
internal data class RootIpv4(val start: Long, val prefix: Int) {
    val end: Long get() = start or (MASK xor mask(prefix))
    val cidr: String get() = "${address(start)}/$prefix"

    /** 两个区间有交集就不能同时作为真实网络与虚拟 TUN 网段。 */
    fun overlaps(other: RootIpv4): Boolean = start <= other.end && other.start <= end

    companion object {
        private const val MASK = 0xffffffffL

        /** 仅接收 IPv4 CIDR，传入选项已经由配置层限制为地址形式。 */
        fun parse(value: String): RootIpv4 {
            val parts = value.split('/')
            require(parts.size == 2) { "无效 IPv4 网段：$value" }
            val ip = InetAddress.getByName(parts[0])
            require(ip is Inet4Address) { "不是 IPv4 网段：$value" }
            return from(ip.address, parts[1].toInt())
        }

        /** 接口地址转换为实际的网络地址。 */
        fun from(bytes: ByteArray, prefix: Int): RootIpv4 {
            require(prefix in 0..32) { "无效 IPv4 前缀：$prefix" }
            val value = bytes.fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 255) }
            return RootIpv4(value and mask(prefix), prefix)
        }

        /** 返回指定前缀的 32 位掩码。 */
        private fun mask(prefix: Int): Long = if (prefix == 0) 0 else (MASK shl (32 - prefix)) and MASK

        /** 把无符号地址值转成 shell 命令可用的点分地址。 */
        fun address(value: Long): String = (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 255).toString() }
    }
}
