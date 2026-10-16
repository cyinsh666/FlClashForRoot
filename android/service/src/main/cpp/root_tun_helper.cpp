#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

// 只报告失败的系统调用，不输出订阅、节点和认证信息。
static int failure(const char *stage) {
    std::fprintf(stderr, "RootProxy 阶段=%s 原因=%s\n", stage, std::strerror(errno));
    return 1;
}

// 独立 Root 可执行程序创建非持久 TUN，然后把 FD 所有权交给应用。
int main(int argc, char **argv) {
    if (argc != 4 || std::strlen(argv[1]) >= sizeof(sockaddr_un::sun_path) - 1 ||
        std::strlen(argv[2]) >= IFNAMSIZ) return 2;
    const int tun = open("/dev/net/tun", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (tun < 0) return failure("打开TUN");
    ifreq request{};
    request.ifr_flags = IFF_TUN | IFF_NO_PI;
    std::snprintf(request.ifr_name, IFNAMSIZ, "%s", argv[2]);
    if (ioctl(tun, TUNSETIFF, &request) < 0) return failure("创建TUN");
    const int connection = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (connection < 0) return failure("创建本地连接");
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    std::memcpy(address.sun_path + 1, argv[1], std::strlen(argv[1]));
    const auto length = offsetof(sockaddr_un, sun_path) + 1 + std::strlen(argv[1]);
    if (connect(connection, reinterpret_cast<sockaddr *>(&address), length) < 0)
        return failure("连接应用");
    // 会话 token 和 FD 在同一条消息传递，应用还会检查连接者为 Root UID。
    iovec data{argv[3], std::strlen(argv[3])};
    alignas(cmsghdr) char ancillary[CMSG_SPACE(sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &data;
    message.msg_iovlen = 1;
    message.msg_control = ancillary;
    message.msg_controllen = sizeof(ancillary);
    cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    std::memcpy(CMSG_DATA(header), &tun, sizeof(tun));
    const bool sent = sendmsg(connection, &message, MSG_NOSIGNAL) == static_cast<ssize_t>(data.iov_len);
    close(connection);
    close(tun);
    return sent ? 0 : failure("传递TUN");
}
