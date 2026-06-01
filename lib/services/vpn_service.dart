import 'package:flutter/services.dart';

// Shared channel — one instance, referenced by all methods
const _ch  = MethodChannel('com.aegis.app/vpn');
const _evt = EventChannel('com.aegis.app/log');

// ── Data classes ──────────────────────────────────────────────────────────────

class LogEntry {
  final String domain;
  final bool blocked;
  final DateTime time;
  const LogEntry({required this.domain, required this.blocked, required this.time});

  factory LogEntry.fromWire(String wire) {
    final p = wire.split('|');
    return LogEntry(
      domain:  p[0],
      blocked: p[1] == '1',
      time:    DateTime.fromMillisecondsSinceEpoch(int.parse(p[2])),
    );
  }
}

class DomainCheckResult {
  final String domain;
  final bool blocked;
  final String? error;
  const DomainCheckResult({required this.domain, required this.blocked, this.error});
}

class HealthStatus {
  final bool vpnRunning, dnsServerAlive, blocklistLoaded,
             upstreamReachable, adDomainBlocked, safeDomainAllowed;
  final int  domainCount, upstreamLatencyMs;
  final String? errorMessage;

  const HealthStatus({
    required this.vpnRunning, required this.dnsServerAlive,
    required this.blocklistLoaded, required this.domainCount,
    required this.upstreamReachable, required this.upstreamLatencyMs,
    required this.adDomainBlocked, required this.safeDomainAllowed,
    this.errorMessage,
  });

  factory HealthStatus.fromMap(Map raw) => HealthStatus(
    vpnRunning:        raw['vpnRunning']        as bool? ?? false,
    dnsServerAlive:    raw['dnsServerAlive']    as bool? ?? false,
    blocklistLoaded:   raw['blocklistLoaded']   as bool? ?? false,
    domainCount:       raw['domainCount']       as int?  ?? 0,
    upstreamReachable: raw['upstreamReachable'] as bool? ?? false,
    upstreamLatencyMs: raw['upstreamLatencyMs'] as int?  ?? -1,
    adDomainBlocked:   raw['adDomainBlocked']   as bool? ?? false,
    safeDomainAllowed: raw['safeDomainAllowed'] as bool? ?? false,
    errorMessage:      raw['errorMessage']      as String?,
  );

  factory HealthStatus.error(String msg) => HealthStatus(
    vpnRunning: false, dnsServerAlive: false, blocklistLoaded: false,
    domainCount: 0, upstreamReachable: false, upstreamLatencyMs: -1,
    adDomainBlocked: false, safeDomainAllowed: false, errorMessage: msg,
  );

  bool get allGood => vpnRunning && dnsServerAlive && blocklistLoaded &&
      upstreamReachable && adDomainBlocked && safeDomainAllowed;
}

// ── VpnService ────────────────────────────────────────────────────────────────

class VpnService {
  // ── VPN control ────────────────────────────────────────────────────────────
  static Future<bool> start() async {
    try { return await _ch.invokeMethod<bool>('startVpn') ?? false; }
    catch (_) { return false; }
  }
  static Future<void> stop() async {
    try { await _ch.invokeMethod('stopVpn'); } catch (_) {}
  }
  static Future<bool> isRunning() async {
    try { return await _ch.invokeMethod<bool>('isVpnRunning') ?? false; }
    catch (_) { return false; }
  }

  // ── Stats ───────────────────────────────────────────────────────────────────
  static Future<int> getAdsBlocked() async {
    try { return await _ch.invokeMethod<int>('getAdsBlocked') ?? 0; }
    catch (_) { return 0; }
  }
  static Future<int> getDomainCount() async {
    try { return await _ch.invokeMethod<int>('getDomainCount') ?? 0; }
    catch (_) { return 0; }
  }
  static Future<DateTime?> getLastUpdate() async {
    try {
      final ms = await _ch.invokeMethod<int>('getLastUpdate');
      return (ms == null || ms == 0) ? null : DateTime.fromMillisecondsSinceEpoch(ms);
    } catch (_) { return null; }
  }
  static Future<List<Map<String, dynamic>>> getStats() async {
    try {
      final raw = await _ch.invokeMethod<List<dynamic>>('getStats');
      return raw?.map((e) => Map<String, dynamic>.from(e as Map)).toList() ?? [];
    } catch (_) { return []; }
  }
  static Future<List<Map<String, dynamic>>> getTopDomains() async {
    try {
      final raw = await _ch.invokeMethod<List<dynamic>>('getTopDomains');
      return raw?.map((e) => Map<String, dynamic>.from(e as Map)).toList() ?? [];
    } catch (_) { return []; }
  }

  // ── Log ─────────────────────────────────────────────────────────────────────
  static Future<List<LogEntry>> getLogSnapshot() async {
    try {
      final raw = await _ch.invokeMethod<List<dynamic>>('getLogSnapshot');
      return raw?.map((e) => LogEntry.fromWire(e as String)).toList() ?? [];
    } catch (_) { return []; }
  }
  static Stream<LogEntry> get logStream =>
      _evt.receiveBroadcastStream().map((e) => LogEntry.fromWire(e as String));

  // ── Debug ───────────────────────────────────────────────────────────────────
  static Future<DomainCheckResult> checkDomain(String domain) async {
    try {
      final raw = await _ch.invokeMethod<Map>('checkDomain', {'domain': domain});
      return DomainCheckResult(
        domain:  raw?['domain'] as String? ?? domain,
        blocked: raw?['blocked'] as bool?  ?? false,
      );
    } catch (e) { return DomainCheckResult(domain: domain, blocked: false, error: '$e'); }
  }
  static Future<HealthStatus> getHealthStatus() async {
    try {
      final raw = await _ch.invokeMethod<Map>('getHealthStatus');
      return HealthStatus.fromMap(raw ?? {});
    } catch (e) { return HealthStatus.error('$e'); }
  }

  // ── DNS / HTTPS settings ────────────────────────────────────────────────────
  static Future<void>    setDohUrl(String? url)     async { try { await _ch.invokeMethod('setDohUrl', {'url': url}); } catch (_) {} }
  static Future<String?> getDohUrl()                async { try { return await _ch.invokeMethod<String?>('getDohUrl'); } catch (_) { return null; } }
  static Future<void>    setHttpsFiltering(bool on) async { try { await _ch.invokeMethod('setHttpsFiltering', {'enabled': on}); } catch (_) {} }
  static Future<bool>    getHttpsFiltering()        async { try { return await _ch.invokeMethod<bool>('getHttpsFiltering') ?? false; } catch (_) { return false; } }
  static Future<bool>    isCaInstalled()            async { try { return await _ch.invokeMethod<bool>('isCaInstalled') ?? false; } catch (_) { return false; } }
  static Future<String?> exportCaCert()             async { try { return await _ch.invokeMethod<String?>('exportCaCert'); } catch (_) { return null; } }

  // ── Exclusions ──────────────────────────────────────────────────────────────
  static Future<List<String>> getExclusions() async {
    try {
      final raw = await _ch.invokeMethod<List<dynamic>>('getExclusions');
      return raw?.cast<String>() ?? [];
    } catch (_) { return []; }
  }
  static Future<void> addExclusion(String pkg)    async { try { await _ch.invokeMethod('addExclusion',    {'package': pkg}); } catch (_) {} }
  static Future<void> removeExclusion(String pkg) async { try { await _ch.invokeMethod('removeExclusion', {'package': pkg}); } catch (_) {} }

  // ── Custom rules ────────────────────────────────────────────────────────────
  static Future<Map<String, List<String>>> getCustomRules() async {
    try {
      final raw = await _ch.invokeMethod<Map>('getCustomRules');
      return {
        'block': List<String>.from(raw?['block'] ?? []),
        'allow': List<String>.from(raw?['allow'] ?? []),
      };
    } catch (_) { return {'block': [], 'allow': []}; }
  }
  static Future<void> addCustomBlock(String domain) async { try { await _ch.invokeMethod('addCustomBlock', {'domain': domain}); } catch (_) {} }
  static Future<void> removeCustomBlock(String domain) async { try { await _ch.invokeMethod('removeCustomBlock', {'domain': domain}); } catch (_) {} }
  static Future<void> addCustomAllow(String domain) async { try { await _ch.invokeMethod('addCustomAllow', {'domain': domain}); } catch (_) {} }
  static Future<void> removeCustomAllow(String domain) async { try { await _ch.invokeMethod('removeCustomAllow', {'domain': domain}); } catch (_) {} }
}
