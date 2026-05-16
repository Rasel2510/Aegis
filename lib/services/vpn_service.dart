import 'package:flutter/services.dart';

class VpnService {
  static const _channel = MethodChannel('com.aegis.app/vpn');

  static Future<bool> start() async {
    try {
      final result = await _channel.invokeMethod<bool>('startVpn');
      return result ?? false;
    } catch (e) {
      return false;
    }
  }

  static Future<void> stop() async {
    try {
      await _channel.invokeMethod('stopVpn');
    } catch (_) {}
  }

  static Future<bool> isRunning() async {
    try {
      final result = await _channel.invokeMethod<bool>('isVpnRunning');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<int> getAdsBlocked() async {
    try {
      final result = await _channel.invokeMethod<int>('getAdsBlocked');
      return result ?? 0;
    } catch (_) {
      return 0;
    }
  }

  static Future<int> getDomainCount() async {
    try {
      final result = await _channel.invokeMethod<int>('getDomainCount');
      return result ?? 0;
    } catch (_) {
      return 0;
    }
  }

  static Future<DateTime?> getLastUpdate() async {
    try {
      final result = await _channel.invokeMethod<int>('getLastUpdate');
      if (result == null || result == 0) return null;
      return DateTime.fromMillisecondsSinceEpoch(result);
    } catch (_) {
      return null;
    }
  }
}
