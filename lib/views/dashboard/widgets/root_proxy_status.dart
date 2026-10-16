import 'dart:math';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/dashboard/widget_metrics.dart';
import 'package:fl_clash/views/transparent_proxy/index.dart';
import 'package:fl_clash/views/transparent_proxy/root_proxy_actions.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

class RootProxyStatusCard extends ConsumerStatefulWidget {
  const RootProxyStatusCard({super.key});

  /// 首页仅编辑 Root 模式开关，实际接管状态在已有设置页查看。
  @override
  ConsumerState<RootProxyStatusCard> createState() =>
      _RootProxyStatusCardState();
}

class _RootProxyStatusCardState extends ConsumerState<RootProxyStatusCard> {
  // 防止保存尚未完成时重复切换，不能用它代替原生运行状态。
  var _busy = false;
  var _active = true;
  static const _switchHeight = kMinInteractiveDimension - 8;

  /// 编辑拖动可能暂时移出卡片，异步保存不能在此时触发重建。
  @override
  void deactivate() {
    _active = false;
    super.deactivate();
  }

  /// 重新放入网格后继续跟随配置，不恢复任何轮询任务。
  @override
  void activate() {
    super.activate();
    _active = true;
  }

  /// 复用设置页保存事务，运行时的切换提示仍由现有配置监听负责。
  Future<void> _setEnabled(bool enabled) async {
    if (_busy) return;
    final container = ProviderScope.containerOf(context, listen: false);
    final next = container
        .read(vpnSettingProvider)
        .copyWith(rootEnabled: enabled);
    setState(() => _busy = true);
    try {
      await saveRootProxySettings(container, next);
    } catch (error) {
      if (mounted && _active) {
        context.showNotifier(compactError(error), level: MessageLevel.error);
      }
    } finally {
      if (mounted && _active) {
        setState(() => _busy = false);
      } else {
        _busy = false;
      }
    }
  }

  /// 卡片点击沿用现有透明代理页面，不新增设置或启停流程。
  void _openSettings() {
    BaseNavigator.push(context, const TransparentProxyView());
  }

  /// 与 VPN 小卡片使用相同的标题、间距和开关布局。
  @override
  Widget build(BuildContext context) {
    final enabled = ref.watch(
      vpnSettingProvider.select((settings) => settings.rootEnabled),
    );
    final l = context.appLocalizations;
    final inset = DashboardWidgetMetrics.insetOf(context);
    final lineHeight =
        globalState.measure.bodyMediumHeight *
            DashboardWidgetMetrics.textScaleOf(context) +
        2;
    final overhang = max(0.0, (_switchHeight - lineHeight) / 2);
    return SizedBox(
      height: DashboardWidgetMetrics.heightOf(context, 1),
      child: CommonCard(
        radius: DashboardWidgetMetrics.radiusOf(context),
        infoPadding: DashboardWidgetMetrics.paddingOf(
          context,
        ).copyWith(bottom: 0),
        info: Info(label: l.rootDashboardStatus, glyph: AppGlyphs.key),
        onPressed: _openSettings,
        child: Padding(
          padding: EdgeInsets.fromLTRB(
            inset,
            0,
            inset,
            DashboardWidgetMetrics.verticalInsetOf(context) - overhang,
          ),
          child: OverflowBox(
            alignment: Alignment.bottomCenter,
            maxHeight: double.infinity,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Flexible(
                  child: TooltipText(
                    text: Text(
                      enabled ? l.enabled : l.disabled,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: context.textTheme.titleSmall
                          ?.adjustSize(-2)
                          .toLight,
                    ),
                  ),
                ),
                Switch(
                  padding: EdgeInsets.zero,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  value: enabled,
                  onChanged: _busy ? null : _setEnabled,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
