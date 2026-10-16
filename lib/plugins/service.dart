import 'dart:async';
import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/core/event.dart';
import 'package:fl_clash/core/method.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

final _utf8JsonDecoder = utf8.decoder.fuse(json.decoder);

Object? _decodeUtf8Json(Uint8List data) => _utf8JsonDecoder.convert(data);

Future<Object?> _decodeResponse(Uint8List data) async {
  if (data.length < 51200) {
    return _decodeUtf8Json(data);
  }
  return compute(_decodeUtf8Json, data);
}

abstract mixin class ServiceListener {
  void onServiceEvent(CoreEvent event) {}

  void onServiceStopped() {}
}

class Service {
  static Service? _instance;
  late MethodChannel methodChannel;

  final ObserverList<ServiceListener> _listeners =
      ObserverList<ServiceListener>();

  int _commandRevision = 0;
  bool _startRequested = false;

  factory Service() {
    _instance ??= Service._internal();
    return _instance!;
  }

  Service._internal() {
    methodChannel = const MethodChannel('$packageName/service');
    methodChannel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'event':
          final data = call.arguments as String? ?? '';
          final methodCall = CoreMethodCall.fromJson(
            Map<String, Object?>.from(json.decode(data) as Map),
          );
          for (final event in coreEventsFromData(methodCall.arguments)) {
            _dispatch(
              'Core event ${event.type.name}',
              (listener) => listener.onServiceEvent(event),
            );
          }
          break;
        case 'stopped':
          // A report stamped before the newest command no longer describes it.
          if (call.arguments != _commandRevision || !_startRequested) {
            break;
          }
          _startRequested = false;
          _dispatch('stop report', (listener) => listener.onServiceStopped());
          break;
        default:
          throw MissingPluginException();
      }
    });
  }

  Future<CoreMethodResponse?> invokeMethod(CoreMethodCall call) async {
    final data = await methodChannel.invokeMethod<Uint8List>(
      'invokeMethod',
      json.encode(call),
    );
    if (data == null) {
      return null;
    }
    final dataJson = await _decodeResponse(data);
    return CoreMethodResponse.fromJson(dataJson as Map<String, dynamic>);
  }

  void _dispatch(String label, void Function(ServiceListener) deliver) {
    for (final listener in List.of(_listeners)) {
      try {
        deliver(listener);
      } catch (error) {
        commonPrint.log(
          'Unable to dispatch Android $label: $error',
          logLevel: LogLevel.error,
        );
      }
    }
  }

  Future<bool> start() async {
    _startRequested = true;
    return await methodChannel.invokeMethod<bool>(
          'start',
          ++_commandRevision,
        ) ??
        false;
  }

  Future<bool> stop() async {
    _startRequested = false;
    return await methodChannel.invokeMethod<bool>('stop', ++_commandRevision) ??
        false;
  }

  Future<String> init() async {
    return await methodChannel.invokeMethod<String>('init') ?? '';
  }

  Future<String> syncState(SharedState state) async {
    return await methodChannel.invokeMethod<String>(
          'syncState',
          json.encode(state),
        ) ??
        '';
  }

  /// 读取原生 Root 会话快照，配置开关不代表流量已被接管。
  Future<Map<String, Object?>> rootStatus() async {
    final status = await methodChannel.invokeMapMethod<String, Object?>(
      'rootStatus',
    );
    return status ?? const {};
  }

  /// 检查 Root 授权和内核能力，返回原生能力摘要。
  Future<String> rootCheck() async {
    return await methodChannel.invokeMethod<String>('rootCheck') ?? '';
  }

  /// 由原生生命周期锁确认停止后清理残留规则，空字符串表示成功。
  Future<String> rootRepair() async {
    return await methodChannel.invokeMethod<String>('rootRepair') ?? '';
  }

  /// 等待原生 Root 停止事务完成，供统一生命周期在销毁 TUN 前使用。
  Future<bool> stopRoot() async {
    _startRequested = false;
    return await methodChannel.invokeMethod<bool>(
          'rootStop',
          ++_commandRevision,
        ) ??
        false;
  }

  /// 打开 Android 热点设置，热点的启停仍由系统管理。
  Future<bool> rootHotspotSettings() async {
    return await methodChannel.invokeMethod<bool>('rootHotspotSettings') ??
        false;
  }

  Future<bool> shutdown() async {
    return await methodChannel.invokeMethod<bool>('shutdown') ?? true;
  }

  Future<DateTime?> getRunTime() async {
    final ms = await methodChannel.invokeMethod<int>('getRunTime') ?? 0;
    if (ms == 0) {
      return null;
    }
    return DateTime.fromMillisecondsSinceEpoch(ms);
  }

  bool get hasListeners {
    return _listeners.isNotEmpty;
  }

  void addListener(ServiceListener listener) {
    _listeners.add(listener);
  }

  void removeListener(ServiceListener listener) {
    _listeners.remove(listener);
  }
}

Service? get service => system.isAndroid ? Service() : null;
