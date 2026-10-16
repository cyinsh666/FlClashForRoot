package com.follow.clash.service.root

import android.content.Context
import android.net.LocalServerSocket
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Root helper 只创建 TUN；收到 FD 后数据面仍使用普通 App UID 的现有 Core。 */
internal object RootTunBridge {
    /** 返回归调用者所有的 FD，调用者通过 detachFd 交给 Core 或在失败时关闭。 */
    fun create(context: Context, shell: RootShell, name: String): ParcelFileDescriptor {
        val token = UUID.randomUUID().toString()
        val address = "flclash-root-$token"
        val helper = File(context.applicationInfo.nativeLibraryDir, "libroot_tun_helper.so")
        check(helper.isFile && helper.canExecute()) { "当前安装包缺少可执行的 Root TUN helper，请重新安装完整 APK" }
        val server = LocalServerSocket(address)
        val executor = Executors.newSingleThreadExecutor()
        val transferLock = Any()
        var pending: ParcelFileDescriptor? = null
        var abandoned = false
        val receiver = executor.submit<ParcelFileDescriptor> {
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                check(socket.peerCredentials.uid == 0) { "TUN helper 连接没有 Root 权限" }
                val bytes = token.toByteArray(Charsets.UTF_8)
                val buffer = ByteArray(bytes.size)
                var count = socket.inputStream.read(buffer)
                val descriptors = socket.ancillaryFileDescriptors.orEmpty()
                try {
                    check(count > 0) { "Root helper 返回为空" }
                    while (count < buffer.size) {
                        val next = socket.inputStream.read(buffer, count, buffer.size - count)
                        check(next > 0) { "Root helper 返回不完整" }
                        count += next
                    }
                    check(buffer.contentEquals(bytes)) { "Root helper 会话不匹配" }
                    check(descriptors.size == 1) { "Root helper 没有提供唯一的 TUN FD" }
                    val descriptor = ParcelFileDescriptor.dup(descriptors.single())
                    synchronized(transferLock) {
                        if (abandoned) {
                            descriptor.close()
                            error("Root TUN 接收已经取消")
                        }
                        pending = descriptor
                    }
                    descriptor
                } finally {
                    descriptors.forEach { descriptor -> runCatching { android.system.Os.close(descriptor) } }
                }
            }
        }
        try {
            shell.run(listOf(helper.absolutePath, address, name, token).joinToString(" ") { RootShell.quote(it) })
            val descriptor = receiver.get(12, TimeUnit.SECONDS)
            synchronized(transferLock) { pending = null }
            return descriptor
        } finally {
            synchronized(transferLock) {
                abandoned = true
                runCatching { pending?.close() }
                pending = null
            }
            // 清理异常不能覆盖已移交的 FD 返回，也不能跳过后面的线程释放。
            runCatching { server.close() }
            receiver.cancel(true)
            executor.shutdownNow()
        }
    }
}
