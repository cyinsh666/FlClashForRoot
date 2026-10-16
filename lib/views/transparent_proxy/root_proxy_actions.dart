import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/plugins/service.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 设置页和首页快捷入口共用持久化与后台同步，避免两处保存不同配置。
Future<void> saveRootProxySettings(
  ProviderContainer container,
  VpnProps next,
) async {
  if (next.rootEnabled && !next.rootLocal && !next.rootHotspot) {
    throw MessageException(currentAppLocalizations.rootSelectScopeTip);
  }
  container.read(vpnSettingProvider.notifier).value = next;
  final saved = await preferences.saveConfig(container.read(configProvider));
  if (!saved) {
    throw MessageException(currentAppLocalizations.rootSettingsSaveFailed);
  }
  await container.read(vpnOptionsProvider.future);
  final sharedState = container.read(sharedStateProvider);
  await preferences.saveShareState(sharedState);
  final error = await service!.syncState(sharedState.needSyncSharedState);
  if (error.isNotEmpty) throw MessageException(error);
}

/// 规则清理只能在统一停止事务确认后执行，不能和新的 Root 启动并行。
Future<void> repairRootProxyNetwork(ProviderContainer container) async {
  final stopped = await container
      .read(setupActionProvider.notifier)
      .setRunning(false);
  if (!stopped) {
    throw MessageException(currentAppLocalizations.rootStopFailed);
  }
  final error = await service!.rootRepair();
  if (error.isNotEmpty) throw MessageException(error);
}
