# Android 端 Root 透明代理与热点共享改造方案

更新日期：2026-10-16。本文按当前源码和本轮实机排查结果更新，历史设备标识、旧安装包校验值与已失效的调试步骤不再作为当前验证记录。

## 1. 目标与代码基线

在已经 Root 的 Android 手机上，通过 FlClash 接管本机与 Wi-Fi 热点客户端的 IPv4 TCP/UDP 流量。热点客户端无需安装代理软件，也无需手动设置 HTTP/SOCKS 代理。

采用 **Root 创建 TUN 并传递文件描述符，现有应用进程内的 JNI/Mihomo 处理流量**。Root 与原 VPN 模式复用已有配置和启停仲裁，运行时互斥；普通 VPN 仍沿用原有服务路径。

| 项目 | 当前说明 |
| --- | --- |
| 上游项目 | [chen08209/FlClash](https://github.com/chen08209/FlClash) |
| 本轮同步基线 | `main`，`03f955382409a4a7ac1f3f92e46d9fcc6d39f106` |
| 内核子模块 | `core/Clash.Meta`，`8380d0e7462f061a964323e9bf3129a236fcc5d5` |
| 用户指定同步目标 | [cyinsh666/FlClashForRoot](https://github.com/cyinsh666/FlClashForRoot) 的 `root` 分支；推送结果以实际 Git 操作为准 |
| 提权入口 | 现有 `su` 授权；未接入 Sui SDK |
| 数据面 | 应用原有 Mihomo 内核与外部 FD TUN；不运行第二套独立 Root 内核 |

本文不保存订阅、节点、账号凭据、设备序列号或个人目录。源码提交号用于说明实现基线，不代表本轮改动已经提交或推送。

## 2. 功能范围

| 能力 | 实现与边界 |
| --- | --- |
| 本机代理 | 接管所选 UID 范围的 IPv4 TCP/UDP；沿用已有应用选择配置 |
| 热点共享 | 按自动识别或手动指定的 Wi-Fi AP 接口接管 IPv4 流量 |
| DNS | IPv4 UDP/TCP 53 重定向至 TUN DNS；Core DNS 必须就绪后才能安装接管规则 |
| 热点 IPv6 | 拒绝所选 AP 的 IPv6 转发，并在热点可接管期间暂停该 AP 发出的 RA；不实现客户端 IPv6 代理 |
| 本机 IPv6 | 拒绝所选本机 UID 范围的非回环 IPv6 流量；系统 Root DNS 单独处理 |
| 自身流量 | 排除 FlClash UID，避免内核出站回环 |
| 局域网 | 绕过真实已连接网段及特殊地址，不将全部私网一律绕过 |
| 热点开关 | 用户通过系统设置开关热点；应用提供入口和等待状态 |
| 加速适配 | 尝试关闭硬件 offload；对已识别的系统 tether BPF 使用自有 TC 规则接回常规 IP 路径 |
| 失败恢复 | 撤销本会话规则并恢复所改设置；清理失败时保留状态与恢复记录，阻止继续切换服务 |
| 首页快捷入口 | 与其他小卡片一致的“透明代理”配置开关，点击卡片进入已有设置页 |

未实现完整 IPv6、IPv6 DNS 代答、TPROXY、独立 Root DNS 监听、USB/蓝牙共享专门适配、Magisk 开机脚本或多机型兼容保证。热点经 SNAT 后不承诺按客户端应用识别或逐客户端统计。

## 3. 架构与启动顺序

```text
透明代理设置页 / 首页配置开关
        │ 保存现有配置、同步 SharedState
        ▼
SetupAction → ServiceState / ServiceController
                              │
                      RootProxyService
                        ├─ RootShell：su 授权与系统规则
                        ├─ Root TUN helper：创建 fcr<进程号>，传递 FD
                        ├─ RootTunBridge：接收 SCM_RIGHTS 文件描述符
                        ├─ Core.startTun：复用现有 JNI / Mihomo
                        └─ RootNetwork / RootProxyStatus：网络与真实状态

本机所选 UID / 热点 IPv4 → 策略路由 → Root TUN → 已有代理规则 → 上游
                              ↑
                     热点回程通过专用路由返回 AP
```

1. 现有生命周期仲裁确认旧服务已停止，恢复历史残留规则。
2. 应用先准备 Root 所需 Core 配置；Root 配置强制启用已有 DNS，关闭 Core 自行安装 iptables 的能力，避免两套接管规则同时存在。
3. `su` 运行 `libroot_tun_helper.so`。辅助程序创建非持久 TUN，通过本地 Unix socket 传递 FD；应用检查 Root 对端及本次会话 token。
4. 配置 TUN 地址、MTU 和恢复脚本，再将 FD 交给现有 Core。启动失败会沿调用链返回，不能把请求应答当成数据面已就绪。
5. Core DNS 与 TUN 就绪后，安装所选本机、热点范围的路由与防火墙规则，发布实际状态。
6. 停止时先撤规则、恢复设置，再关闭 TUN 和服务。清理失败保留资源归属，用户可重试停止或恢复网络。

辅助程序由 `android/service/build.gradle.kts` 使用项目 NDK 编译，按目标 ABI 打包；其 `.so` 名称用于 APK 原生库目录打包，实际是 PIE 可执行程序。数据面保持普通应用 UID，Root 权限不传播给 Go 内核。

## 4. 路由与 IPv4 DNS

### 4.1 接口与地址选择

- 从 `ConnectivityManager` 读取当前上游；发现其他系统 VPN 时提示冲突。
- 从网络接口和 `dumpsys tethering` 的 `TetheredState` 识别热点，不假定固定接口名。手动接口必须真实存在，且不能是当前上游。
- 启动时检查设备所有已有 IPv4 前缀，选择不冲突的 `/30`。优先 `198.18.0.0/30`，冲突后从候选私网中选择；`.1` 为 TUN 地址、`.2` 为 DNS 地址。
- TUN 与 Core 使用同一 MTU；读取已有设置，未配置时默认 9000，不将某次 MTU 调整当作热点问题已确认的原因。
- 每 3 秒读取拓扑；完整规则健康检查与失败热点重试最多每 30 秒执行。拓扑变化或检查失败时才重装普通接管规则。

### 4.2 接管与回程

- 从 18000～18100 选择未使用的 IPv4 路由表，从 9000～9900 按组选择策略规则优先级。
- 配置指定 IPv4 路由，未指定时接管全部 IPv4；对真实已连接网段、回环、链路本地、组播等设置 `throw`。
- 本机使用 `iif lo + uidrange`，沿用应用白名单或黑名单，排除 FlClash UID 与 Root 系统服务 UID。UID 0 的普通出站保留原网络，DNS 单独处理。
- 热点使用真实 AP 的 `iif`，向 TUN 做 MASQUERADE，并添加 AP 子网及 TUN 入方向规则，让回程返回客户端。
- 增加面向 TUN 子网的精确策略规则，让 system/mixed 栈的隧道地址回复不受应用 UID 绕过策略影响。
- IPv4 filter 只增加热点与 TUN 之间所需的转发规则，不清空 Android 或其他工具的防火墙。

### 4.3 DNS

本机所选 UID 的 OUTPUT 和选中 AP 的 PREROUTING，将 IPv4 UDP/TCP 53 DNAT 到 TUN DNS。启用本机接管且有实际 UID 范围时，也接管 UID 0 的 IPv4 DNS，以覆盖系统 DNS 服务；UID 0 的非回环 IPv6 DNS 被拒绝，其余系统 IPv6 不因此全部阻断。

DNS 上游、规则和增强模式复用现有 Core 配置。本轮排查使用过 redir-host 和加密 DNS 上游，这属于设备当时的有效配置，不把某个公共 DNS 或全局代理模式写死为功能前提。加密 DNS 连接按普通流量及已有内核规则处理。

## 5. 热点 IPv6 DNS 与 RA 的最终处理

### 5.1 实际问题与取舍

排查中，热点客户端通过 IPv4 TCP 访问多个 Google/YouTube 端点成功，但原生应用仍报 DNS 解析失败。客户端网络配置同时包含热点 IPv4 网关 DNS 和 AP 通告的全球 IPv6 DNS。原 IPv4 DNS 重定向不能处理这些 IPv6 DNS；仅拒绝 IPv6 FORWARD 也不能覆盖目的地是 AP 自身地址的 DNS INPUT。

实机没有 IPv6 nat 表，因此放弃 IPv6 REDIRECT、额外 DNS 监听等尝试。最终保持当前 IPv4 代理范围，从地址通告侧避免新连接客户端继续取得不可用的 IPv6 DNS。

### 5.2 独立 RA 链

仅当热点已满足接管条件时，在 IPv6 filter OUTPUT 挂接本会话独立的 `FCR_<进程号>_RA` 链，并对选中 AP 添加：

```text
-o <热点接口> -p ipv6-icmp --icmpv6-type router-advertisement -j DROP
```

该规则同时覆盖周期组播和响应客户端 RS 的单播 RA，不限制目的地址。它只暂停选中 AP 发出的 RA，不删除 AP 的 IPv6 地址、不关闭上游 IPv6，也不新增 IPv6 DNS 服务。

RA 保护在普通路由与防火墙重装前就绪，普通 `clear_routes` 保留独立 RA 链，避免网络变化重装期间短暂重新通告。接口切换时先添加新接口保护，再删除旧接口规则；健康检查核对链入口与每个 AP 的精确规则。热点不可接管、关闭、服务停止及恢复流程均撤销自有 RA 链，让系统恢复后续通告。

AOSP `RouterAdvertisementDaemon` 使用 `AF_INET6/SOCK_RAW/IPPROTO_ICMPV6` socket 绑定下行接口，通过 `Os.sendto` 发送 RA。Android 通用内核的 RAW IPv6 发送路径进入 `ip6_local_out` 和 `NF_INET_LOCAL_OUT`，因此 AOSP 路径可由 OUTPUT 过滤。厂商实现是否完全一致仍须结合设备行为判断；源码机制不能替代该设备的规则命中证据。

### 5.3 客户端重连与边界

启用后让客户端断开并重新连接热点，重新取得 IPv4 DNS。已经缓存的 IPv6 DNS、地址和默认路由不会因 DROP 新 RA 立即撤销；必要时确认客户端网络配置已经更新，再重新打开应用。

这里实现的是热点 IPv4 代理与 IPv6 通告限制。客户端真正的 IPv6 连接没有进入代理；手动或仍缓存的 IPv6 DNS 也不会被本方案代答。停止后系统在后续 RA 周期或客户端 RS 时恢复通告，不承诺客户端配置瞬间恢复。

## 6. 热点加速兼容性

硬件 offload 和 eBPF 可能绕过普通 IP 转发。尝试设置 `tether_offload_disabled=1`；设备支持时设置 `override_tether_enable_bpf_offload=false`。若硬件 HAL 仍在运行，显示等待状态，用户需重开系统热点。

`RootIngressGuard` 在热点 ingress 空闲的优先级 1 添加自有 u32 终止分类节点，将报文交回常规网络栈。规则使用本会话句柄和 `flowid fc01:1` 标识，不删除系统 BPF、map 或 clsact qdisc。

当前识别 AOSP tether upstream 程序，以及实机确认的 Oplus `prog_oplus-netd_schedcls_ingress_data_redirect`。Oplus action 仅允许明确的 `Egress Redirect to device ifb<数字>`；未知程序、未知 action 或优先级冲突仍报告不可接管。安装前持久化撤销脚本，停止、下游消失和恢复时仅删除自有节点。

## 7. 生命周期、状态与页面

所有启停、重启和恢复操作仍由现有 `SetupAction`、`ServiceState`、`ServiceController` 收敛，不新增页面专属服务生命周期。Root 模式准备和运行配置不能被 UI 暂停状态当作关闭数据面的依据；厂商后台限制仍需按设备验证。

链名前缀为 `FCR_`。撤销只作用于本会话链、策略规则、路由表和 TC 节点；所改系统设置仅在当前值仍等于本会话写入值时恢复。恢复脚本位于应用私有目录，成功清理才删除。Root 被撤销、进程强杀或系统中途退出时可能无法即时清理，页面保留失败状态和恢复入口。

设置页位于 Android“工具 → 设置 → 透明代理”。进入页面只读取状态，不自动申请 Root、不启动服务；保留授权检查、接管范围、接口选择、应用并重启、停止和恢复网络操作。

| 配置字段 | 默认值 | 含义 |
| --- | --- | --- |
| `rootEnabled` | false | 选择 Root 模式，实际服务状态另行读取 |
| `rootLocal` | true | 接管本机所选应用范围 |
| `rootHotspot` | true | 接管热点 IPv4 流量 |
| `rootInterface` | 空 | 自动识别；非空时选择指定热点接口 |

首页“透明代理”为小卡片，仅提供 `rootEnabled` 开关和设置页入口，不轮询原生状态。保存复用 `saveRootProxySettings`，运行中的配置变化由已有监听提示重启；开关显示配置选择，不能据此判定正在运行或实际已接管。

真实状态使用原生 `phase`、`localActive`、`hotspotActive`、`upstream`、`downstream`、`error`；残留恢复状态另由 `needsRepair` 表示。正常状态历史与必要错误信息保留，用于页面反馈。

## 8. 本轮验证与日志收尾

- 本机 IPv4 代理已恢复可用；热点客户端 shell 对多个 Google/YouTube 端点的 IPv4 TCP 请求成功。这些请求用于缩小问题范围，不等同于所有原生应用已通过验收。
- 原生应用曾有 Cronet `ERR_NAME_NOT_RESOLVED (-105)`，客户端存在 IPv4/IPv6 DNS 混合配置；这支持继续检查 IPv6 DNS 选择路径，但不单凭该错误认定全部原因。
- 已在实机确认 IPv6 nat 不可用，复杂 IPv6 DNS 方案已回退，当前采用独立 RA 保护链。
- 开启 Root 后客户端重连，仅取得一个 IPv4 DNS；停止并重连、等待系统通告后恢复 IPv4/IPv6 两个 DNS；重新开启并重连后再次仅有 IPv4 DNS。
- 最终方案安装与客户端重连后，YouTube 原生应用实际视频播放与 Google Play 原生推荐首页均已确认可用；主机退到后台后请求 YouTube 仍返回 HTTP 200。随后用户反馈目前看起来可用。这些结果不扩展为所有应用、网络切换或机型已验证。
- 本轮没有取得可证明 RA DROP 实际命中的计数证据，因此不声称已通过该计数证明厂商 RA 路径；规则安装检查、机制分析与用户体验属于不同证据。
- 清理诊断代码后，`flutter pub get` 退出码为 0；`flutter analyze --no-fatal-infos` 报告 `No issues found`；Android ARM64 Debug 构建成功。此次收尾未重新安装 APK，不能把构建成功记成清理后的版本已部署。
- 按用户要求不编写、未运行测试用例。旧设备标识、旧 APK 校验值和历史页面性能诊断不再作为本轮产物证明；Git 同步结果以实际提交与推送记录为准。

用户确认修复后，已移除临时 `RootTrafficDiagnostics`、启动及 3/6/30 秒采样、TC 规则 dump、BPF/RA 过程成功日志与仅用于日志去重的状态。原排查前缀已改为常规 `RootProxy`。拓扑观察、低频规则健康检查、Core DNS 就绪校验、失败回传及恢复逻辑保留；必要的 helper 系统调用错误和服务失败信息也保留，避免失去启动、清理失败的可见原因。

后续复核应覆盖本机与热点分别启用、客户端重连、DNS 与实际出口、热点关闭重开、Wi-Fi/蜂窝切换、后台运行、停止及异常恢复。未完成的场景不写成已验证结论。

## 9. 主要实现文件

| 文件/模块 | 职责 |
| --- | --- |
| `lib/views/transparent_proxy/index.dart`、`root_proxy_actions.dart` | 已有设置页、配置保存与恢复事务 |
| `lib/views/dashboard/widgets/root_proxy_status.dart`、`widget_registry.dart` | 首页小卡片与注册 |
| `lib/models/config.dart`、`core.dart`、`lib/common/task.dart` | 配置序列化与 Root 有效配置 |
| `lib/providers/actions/setup.dart`、`lib/manager/vpn_manager.dart` | 复用启停与配置重启提醒 |
| `android/app/.../ServiceStateMachine.kt`、`ServiceController.kt` | 服务模式仲裁、失败和清理归属 |
| `android/app/.../plugins/ServicePlugin.kt` | Root 状态、授权检查与恢复入口 |
| `android/service/.../root/` | TUN 桥接、网络发现、路由、防火墙、RA、TC 和恢复 |
| `android/service/src/main/cpp/root_tun_helper.cpp`、`android/service/build.gradle.kts` | Root helper 与 ABI 打包 |
| `core/config.go`、`core/protocol.go`、`core/tun/tun.go` | Root 配置约束、DNS 就绪和原有 TUN 接入 |
| `arb/`、`lib/l10n/` | 中、英、日、俄文案及生成文件 |

## 10. 参考资料

- [AOSP RouterAdvertisementDaemon](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-16.0.0_r4/Tethering/src/android/net/ip/RouterAdvertisementDaemon.java)：RAW IPv6 RA socket、单播与组播发送路径。
- [Android 通用内核 raw.c](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/net/ipv6/raw.c)、[ip6_output.c](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/net/ipv6/ip6_output.c)、[output_core.c](https://android.googlesource.com/kernel/common/+/refs/heads/android16-6.12/net/ipv6/output_core.c)：IPv6 RAW 发包到 LOCAL_OUT 的路径。
- [AOSP 热点硬件分流说明](https://source.android.com/docs/core/data/tethering-offload)：热点 offload 与普通转发的差异。
- [AOSP BpfCoordinator](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/Tethering/src/com/android/networkstack/tethering/BpfCoordinator.java)：系统 tether BPF 管理。
- [Mihomo TUN](https://wiki.metacubex.one/config/inbound/tun/)：内核 TUN 配置参考，不代表当前设备已支持完整双栈。
