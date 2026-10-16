/// 原生 Root 会话的实际状态，与页面保存的期望配置分开读取。
class RootProxyStatus {
  /// 保存一次原生快照，不自行推导服务是否运行。
  const RootProxyStatus({
    required this.phase,
    required this.error,
    required this.localActive,
    required this.hotspotActive,
    required this.upstream,
    required this.downstream,
    required this.logs,
    required this.needsRepair,
  });

  final String phase;
  final String error;
  final bool localActive;
  final bool hotspotActive;
  final String upstream;
  final List<String> downstream;
  final List<String> logs;
  final bool needsRepair;

  /// MethodChannel 使用原生固定字段，缺失快照不会被当成已接管。
  factory RootProxyStatus.fromMap(Map<String, Object?> value) {
    return RootProxyStatus(
      phase: value['phase'] as String? ?? 'stopped',
      error: value['error'] as String? ?? '',
      localActive: value['localActive'] as bool? ?? false,
      hotspotActive: value['hotspotActive'] as bool? ?? false,
      upstream: value['upstream'] as String? ?? '',
      downstream: (value['downstream'] as List?)?.cast<String>() ?? const [],
      logs: (value['logs'] as List?)?.cast<String>() ?? const [],
      needsRepair: value['needsRepair'] as bool? ?? false,
    );
  }
}
