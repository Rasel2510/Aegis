import 'dart:async';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../services/vpn_service.dart';

class AdBlockProvider extends ChangeNotifier {
  bool _isBlocking   = false;
  bool _darkMode     = false;
  int  _adsBlocked   = 0;
  int  _domainCount  = 0;
  DateTime? _lastUpdate;
  bool _isUpdating   = false;

  // ── Log ──────────────────────────────────────────────────────────────────
  final List<LogEntry> _log = [];
  static const _maxLog = 500;

  List<LogEntry> get log          => List.unmodifiable(_log);
  bool get isBlocking             => _isBlocking;
  bool get darkMode               => _darkMode;
  int  get adsBlocked             => _adsBlocked;
  int  get domainCount            => _domainCount;
  DateTime? get lastUpdate        => _lastUpdate;
  bool get isUpdating             => _isUpdating;

  Timer?       _pollTimer;
  StreamSubscription<LogEntry>? _logSub;

  AdBlockProvider() { _init(); }

  Future<void> _init() async {
    final prefs = await SharedPreferences.getInstance();
    _darkMode    = prefs.getBool('darkMode') ?? false;
    _isBlocking  = await VpnService.isRunning();
    _domainCount = await VpnService.getDomainCount();
    _lastUpdate  = await VpnService.getLastUpdate();

    // Load existing log snapshot (populated even if app was closed and reopened)
    final snapshot = await VpnService.getLogSnapshot();
    _log.addAll(snapshot);

    notifyListeners();
    _startPolling();
    _subscribeToLogStream();
  }

  // ── Stats poll (every 2s) ─────────────────────────────────────────────────

  void _startPolling() {
    _pollTimer = Timer.periodic(const Duration(seconds: 2), (_) async {
      final running = await VpnService.isRunning();
      final count   = await VpnService.getAdsBlocked();
      final domains = await VpnService.getDomainCount();
      final lastUp  = await VpnService.getLastUpdate();

      if (running  != _isBlocking  ||
          count    != _adsBlocked  ||
          domains  != _domainCount) {
        _isBlocking  = running;
        _adsBlocked  = count;
        _domainCount = domains;
        _lastUpdate  = lastUp;
        notifyListeners();
      }
    });
  }

  // ── Live log stream ───────────────────────────────────────────────────────

  void _subscribeToLogStream() {
    _logSub = VpnService.logStream.listen((entry) {
      _log.add(entry);
      if (_log.length > _maxLog) _log.removeAt(0);
      // Don't call notifyListeners() here — too frequent (hundreds/sec).
      // LogScreen listens to the stream directly for smooth updates.
    });
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  Future<bool> toggleBlocking() async {
    if (_isBlocking) {
      await VpnService.stop();
      _isBlocking = false;
      notifyListeners();
      return true;
    } else {
      _isUpdating = true;
      notifyListeners();
      final granted = await VpnService.start();
      _isUpdating = false;
      if (granted) _isBlocking = true;
      notifyListeners();
      return granted;
    }
  }

  void toggleDarkMode(bool val) async {
    _darkMode = val;
    final prefs = await SharedPreferences.getInstance();
    prefs.setBool('darkMode', val);
    notifyListeners();
  }

  // ── Formatters ────────────────────────────────────────────────────────────

  String get domainCountFormatted {
    if (_domainCount == 0) return 'Loading...';
    if (_domainCount >= 1000) {
      return '${(_domainCount / 1000).toStringAsFixed(1)}k';
    }
    return '$_domainCount';
  }

  String get lastUpdateFormatted {
    if (_lastUpdate == null) return 'Never';
    final diff = DateTime.now().difference(_lastUpdate!);
    if (diff.inMinutes < 60) return '${diff.inMinutes}m ago';
    if (diff.inHours   < 24) return '${diff.inHours}h ago';
    return '${diff.inDays}d ago';
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _logSub?.cancel();
    super.dispose();
  }
}
