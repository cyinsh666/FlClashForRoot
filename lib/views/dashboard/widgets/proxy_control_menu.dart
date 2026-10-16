import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/icons/icons.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// 导航栏各布局共用菜单数据，启停仍通过原快捷按钮的统一入口。
List<CommonPopupMenuItem>? dashboardProxyControlMenuItems(
  BuildContext context,
  WidgetRef ref, {
  required bool enabled,
}) {
  final isStart = ref.watch(isStartProvider);
  final connecting = ref.watch(coreStatusProvider) == CoreStatus.connecting;
  if (!enabled) return null;
  final l = context.appLocalizations;
  return [
    CommonPopupMenuItem(
      label: isStart
          ? l.stop
          : connecting
          ? l.connecting
          : l.start,
      glyph: AppGlyphs.playPause(isStart ? 1 : 0),
      onPressed: connecting && !isStart
          ? null
          : () => _setRunning(ref, !isStart),
    ),
  ];
}

/// 菜单可能停留到服务状态变化；点击时重新核对目标状态，避免误切换。
void _setRunning(WidgetRef ref, bool running) {
  if (!ref.context.mounted ||
      !ref.read(appSettingProvider).hideQuickStart ||
      ref.read(profilesProvider).isEmpty ||
      ref.read(isStartProvider) == running ||
      (running && ref.read(coreStatusProvider) == CoreStatus.connecting)) {
    return;
  }
  ref.read(commonActionProvider.notifier).toggleRunning();
}
