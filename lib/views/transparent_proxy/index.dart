import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/plugins/service.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

import 'root_proxy_status.dart';
import 'root_proxy_actions.dart';

class TransparentProxyView extends ConsumerStatefulWidget {
  const TransparentProxyView({super.key});

  /// Root 页面仅展示配置和原生会话，启停仍由统一生命周期负责。
  @override
  ConsumerState<TransparentProxyView> createState() =>
      _TransparentProxyViewState();
}

class _TransparentProxyViewState extends ConsumerState<TransparentProxyView>
    with WidgetsBindingObserver {
  static final _interfacePattern = RegExp(r'^[a-zA-Z0-9_.-]{1,15}$');

  // 原生快照是接管状态的唯一来源，不能用配置开关推导已生效。
  RootProxyStatus? _status;
  Timer? _statusTimer;
  String? _loadError;
  String? _capabilities;
  var _refreshing = false;
  var _busy = false;

  /// 首次轻量读取状态，后台期间不轮询。
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _startStatusPolling();
  }

  /// 退出页面时释放轮询，不改变 Root 服务运行意图。
  @override
  void dispose() {
    _statusTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// 从系统热点设置返回后立即读取新的热点接口状态。
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _startStatusPolling();
    } else {
      _statusTimer?.cancel();
      _statusTimer = null;
    }
  }

  /// 仅保留一个快照定时器，读取过程不触发 Root 授权。
  void _startStatusPolling() {
    _statusTimer?.cancel();
    unawaited(_refreshStatus());
    _statusTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      unawaited(_refreshStatus());
    });
  }

  /// 查询失败时保留上次快照，并展示读取错误。
  Future<void> _refreshStatus() async {
    if (_refreshing || !mounted) return;
    _refreshing = true;
    try {
      final snapshot = await service!.rootStatus();
      if (!mounted) return;
      setState(() {
        _status = RootProxyStatus.fromMap(snapshot);
        _loadError = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _loadError = compactError(error));
    } finally {
      _refreshing = false;
    }
  }

  /// 页面操作串行执行，异常和离开页面都能结束按钮的等待状态。
  Future<void> _runAction(Future<void> Function() action) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await action();
    } catch (error) {
      if (mounted) {
        context.showNotifier(compactError(error), level: MessageLevel.error);
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        unawaited(_refreshStatus());
      }
    }
  }

  /// 同步保存 Flutter 配置和后台共享配置，避免立即退出后丢失选择。
  Future<void> _saveSettings(VpnProps next) async {
    if (next.rootEnabled && !next.rootLocal && !next.rootHotspot) {
      context.showNotifier(
        context.appLocalizations.rootSelectScopeTip,
        level: MessageLevel.warning,
      );
      return;
    }
    final container = ProviderScope.containerOf(context, listen: false);
    await _runAction(() async {
      await saveRootProxySettings(container, next);
    });
  }

  /// 用户主动检查时才请求 Root 授权并显示原生能力摘要。
  Future<void> _checkRoot() async {
    await _runAction(() async {
      final capabilities = await service!.rootCheck();
      if (mounted) setState(() => _capabilities = capabilities);
    });
  }

  /// 经统一停止事务后恢复网络，原生同锁校验阻止与新启动交错。
  Future<void> _repairNetwork() async {
    final container = ProviderScope.containerOf(context, listen: false);
    await _runAction(() async {
      await repairRootProxyNetwork(container);
      if (mounted) {
        context.showNotifier(context.appLocalizations.rootRepairCompleted);
      }
    });
  }

  /// 只打开系统热点设置，不在应用内擅自开启热点。
  Future<void> _openHotspotSettings() async {
    await _runAction(() async {
      if (!await service!.rootHotspotSettings()) {
        throw MessageException(
          currentAppLocalizations.rootHotspotSettingsUnavailable,
        );
      }
    });
  }

  /// 原生阶段使用明确的本地化标签，等待热点时本机仍可保持接管。
  String _phaseLabel(String phase) {
    final l = context.appLocalizations;
    return switch (phase) {
      'stopped' => l.rootPhaseStopped,
      'starting' => l.rootPhaseStarting,
      'active' => l.rootPhaseActive,
      'waitingHotspot' => l.rootPhaseWaitingHotspot,
      'waitingHotspotRestart' => l.rootPhaseWaitingHotspotRestart,
      'failed' => l.rootPhaseFailed,
      'cleanupFailed' => l.rootPhaseCleanupFailed,
      _ => l.rootStatusUnknown,
    };
  }

  /// 复用新版配置行，保存期望配置不会直接启动或重新安装路由。
  Widget _buildSettings() {
    final l = context.appLocalizations;
    return AbsorbPointer(
      absorbing: _busy,
      child: generateSectionV3(
        title: l.options,
        footer: l.rootConfigDescription,
        items: [
          ConfigToggleItem(
            title: (l) => l.rootProxyEnable,
            selector: vpnSettingProvider.select((state) => state.rootEnabled),
            onChanged: (ref, value) {
              unawaited(
                _saveSettings(
                  ref.read(vpnSettingProvider).copyWith(rootEnabled: value),
                ),
              );
            },
          ),
          ConfigToggleItem(
            title: (l) => l.rootProxyLocal,
            subtitle: (l) => l.rootProxyLocalDescription,
            selector: vpnSettingProvider.select((state) => state.rootLocal),
            onChanged: (ref, value) {
              unawaited(
                _saveSettings(
                  ref.read(vpnSettingProvider).copyWith(rootLocal: value),
                ),
              );
            },
          ),
          ConfigToggleItem(
            title: (l) => l.rootProxyHotspot,
            selector: vpnSettingProvider.select((state) => state.rootHotspot),
            onChanged: (ref, value) {
              unawaited(
                _saveSettings(
                  ref.read(vpnSettingProvider).copyWith(rootHotspot: value),
                ),
              );
            },
          ),
          ConfigTextItem(
            title: (l) => l.rootHotspotInterface,
            subtitle: (l) => l.rootHotspotInterfaceDescription,
            selector: vpnSettingProvider.select((state) => state.rootInterface),
            maxLength: 15,
            allowEmpty: true,
            validator: (value, l) {
              final name = value?.trim() ?? '';
              return name.isEmpty || _interfacePattern.hasMatch(name)
                  ? null
                  : l.rootInterfaceInvalid;
            },
            onChanged: (ref, value) {
              unawaited(
                _saveSettings(
                  ref
                      .read(vpnSettingProvider)
                      .copyWith(rootInterface: value.trim()),
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  /// 分别展示本机和热点的真实接管状态，不以总开关代替成功结果。
  Widget _buildStatus() {
    final l = context.appLocalizations;
    final status = _status;
    return generateSectionV3(
      title: l.status,
      items: [
        if (status == null && _loadError == null)
          ListItem(
            title: Text(l.loading),
            subtitle: const SkeletonText(width: 180),
          ),
        if (status != null) ...[
          ListItem(
            title: Text(l.rootSessionStatus),
            subtitle: Text(_phaseLabel(status.phase)),
          ),
          ListItem(
            title: Text(l.rootProxyLocal),
            subtitle: Text(status.localActive ? l.rootActive : l.rootInactive),
          ),
          ListItem(
            title: Text(l.rootProxyHotspot),
            subtitle: Text(
              status.hotspotActive ? l.rootActive : l.rootInactive,
            ),
          ),
          ListItem(
            title: Text(l.rootUpstream),
            subtitle: Text(
              status.upstream.isEmpty ? l.rootNotDetected : status.upstream,
            ),
          ),
          ListItem(
            title: Text(l.rootDownstream),
            subtitle: Text(
              status.downstream.isEmpty
                  ? l.rootNotDetected
                  : status.downstream.join('\n'),
            ),
          ),
          if (status.needsRepair)
            ListItem(
              leading: const GlyphIcon(AppGlyphs.warning),
              title: Text(l.rootNeedsRepair),
              subtitle: Text(l.rootRepairDescription),
            ),
          if (status.error.isNotEmpty)
            ListItem(
              title: Text(l.error),
              subtitle: SelectableText(status.error),
            ),
        ],
        if (_loadError case final error?)
          ListItem(
            title: Text(l.rootStatusReadFailed),
            subtitle: SelectableText(error),
          ),
      ],
    );
  }

  /// 检查与恢复都通过原生专用接口，服务启停不另设入口。
  Widget _buildActions() {
    final l = context.appLocalizations;
    return generateSectionV3(
      title: l.rootMaintenance,
      footer: l.rootRepairDescription,
      items: [
        ListItem(
          leading: const GlyphIcon(AppGlyphs.key),
          title: Text(l.rootCheck),
          trailing: TrailingLoading(isLoading: _busy),
          onTap: _busy ? null : _checkRoot,
        ),
        if (_capabilities case final capabilities?)
          ListItem(
            title: Text(l.rootCapabilities),
            subtitle: SelectableText(capabilities),
          ),
        ListItem(
          leading: const GlyphIcon(AppGlyphs.wifi),
          title: Text(l.rootHotspotSettings),
          onTap: _busy ? null : _openHotspotSettings,
        ),
        ListItem(
          leading: const GlyphIcon(AppGlyphs.refresh),
          title: Text(l.rootRepair),
          onTap: _busy ? null : _repairNetwork,
        ),
      ],
    );
  }

  /// Root 首版的地址族策略明确展示，防止将 IPv6 阻断误认成已代理。
  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final logs = _status?.logs ?? const <String>[];
    return CommonScaffold(
      title: l.rootProxy,
      iconActions: [
        IconButtonData(
          glyph: AppGlyphs.refresh,
          tooltip: l.rootRefreshStatus,
          onPressed: _refreshStatus,
        ),
      ],
      body: Builder(
        builder: (context) => ListView(
          padding: const EdgeInsets.symmetric(horizontal: 16).copyWith(
            top: context.contentTopPadding,
            bottom: 16 + BottomInsetScope.of(context),
          ),
          children: [
            _buildSettings(),
            _buildStatus(),
            generateSectionV3(
              items: [
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.info),
                  title: Text(l.rootAddressPolicy),
                  subtitle: Text(l.rootAddressPolicyDescription),
                ),
              ],
            ),
            _buildActions(),
            if (logs.isNotEmpty)
              generateSectionV3(
                title: l.logs,
                items: [ListItem(title: SelectableText(logs.join('\n')))],
              ),
          ],
        ),
      ),
    );
  }
}
