import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/vpn_service.dart';

/// DAY 5 — DebugScreen
///
/// Three sections:
///   1. Health checks — live status of every subsystem
///   2. Domain tester — type any domain, see instantly if it's blocked
///   3. Log stats    — total queries, block rate, top blocked domains
class DebugScreen extends StatefulWidget {
  const DebugScreen({super.key});

  @override
  State<DebugScreen> createState() => _DebugScreenState();
}

class _DebugScreenState extends State<DebugScreen> {
  // ── Health ────────────────────────────────────────────────────────────────
  HealthStatus? _health;
  bool _healthLoading = false;

  // ── Domain tester ─────────────────────────────────────────────────────────
  final _domainController = TextEditingController();
  final _focusNode = FocusNode();
  DomainCheckResult? _checkResult;
  bool _checkLoading = false;

  // ── Log stats (computed from live stream) ─────────────────────────────────
  int _totalQueries = 0;
  int _totalBlocked = 0;
  final Map<String, int> _topBlocked = {};
  StreamSubscription<LogEntry>? _logSub;

  // Built-in test domains
  static const _testDomains = [
    ('doubleclick.net', true),
    ('googlevideo.com', false),
    ('pagead2.googlesyndication.com', true),
    ('youtube.com', false),
    ('imasdk.googleapis.com', true),
    ('facebook.com', false),
    ('ads.facebook.com', true),
    ('google.com', false),
  ];

  @override
  void initState() {
    super.initState();
    _runHealthCheck();
    _subscribeToLog();
  }

  @override
  void dispose() {
    _domainController.dispose();
    _focusNode.dispose();
    _logSub?.cancel();
    super.dispose();
  }

  // ── Health check ──────────────────────────────────────────────────────────

  Future<void> _runHealthCheck() async {
    setState(() => _healthLoading = true);
    final h = await VpnService.getHealthStatus();
    if (!mounted) return;
    setState(() {
      _health = h;
      _healthLoading = false;
    });
  }

  // ── Domain check ──────────────────────────────────────────────────────────

  Future<void> _checkDomain(String domain) async {
    if (domain.trim().isEmpty) return;
    _focusNode.unfocus();
    setState(() {
      _checkLoading = true;
      _checkResult = null;
    });
    final r = await VpnService.checkDomain(domain.trim());
    if (!mounted) return;
    setState(() {
      _checkResult = r;
      _checkLoading = false;
    });
  }

  // ── Log subscription ──────────────────────────────────────────────────────

  void _subscribeToLog() {
    _logSub = VpnService.logStream.listen((entry) {
      if (!mounted) return;
      setState(() {
        _totalQueries++;
        if (entry.blocked) {
          _totalBlocked++;
          _topBlocked[entry.domain] = (_topBlocked[entry.domain] ?? 0) + 1;
        }
      });
    });
    // Seed from snapshot
    VpnService.getLogSnapshot().then((snap) {
      if (!mounted) return;
      setState(() {
        for (final e in snap) {
          _totalQueries++;
          if (e.blocked) {
            _totalBlocked++;
            _topBlocked[e.domain] = (_topBlocked[e.domain] ?? 0) + 1;
          }
        }
      });
    });
  }

  // ── Build ─────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title:
            const Text('Debug', style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Rerun health checks',
            onPressed: _healthLoading ? null : _runHealthCheck,
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _sectionHeader('System Health', Icons.monitor_heart_outlined),
          const SizedBox(height: 8),
          _HealthCard(health: _health, loading: _healthLoading),
          const SizedBox(height: 24),
          _sectionHeader('Domain Tester', Icons.search),
          const SizedBox(height: 8),
          _DomainTesterCard(
            controller: _domainController,
            focusNode: _focusNode,
            result: _checkResult,
            loading: _checkLoading,
            onCheck: _checkDomain,
            testDomains: _testDomains,
          ),
          const SizedBox(height: 24),
          _sectionHeader('Session Stats', Icons.bar_chart),
          const SizedBox(height: 8),
          _StatsCard(
            total: _totalQueries,
            blocked: _totalBlocked,
            topBlocked: _topBlocked,
          ),
        ],
      ),
    );
  }

  Widget _sectionHeader(String title, IconData icon) {
    return Row(
      children: [
        Icon(icon, size: 18, color: Theme.of(context).colorScheme.primary),
        const SizedBox(width: 8),
        Text(title,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.bold,
              letterSpacing: 0.5,
              color: Theme.of(context).colorScheme.primary,
            )),
      ],
    );
  }
}

// ── Health card ───────────────────────────────────────────────────────────────

class _HealthCard extends StatelessWidget {
  final HealthStatus? health;
  final bool loading;
  const _HealthCard({required this.health, required this.loading});

  @override
  Widget build(BuildContext context) {
    if (loading) {
      return const Card(
        child: Padding(
          padding: EdgeInsets.all(32),
          child: Center(child: CircularProgressIndicator()),
        ),
      );
    }

    final h = health;
    if (h == null) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: const [
              Icon(Icons.info_outline, color: Colors.grey),
              SizedBox(width: 10),
              Text('Tap ↻ to run health checks',
                  style: TextStyle(color: Colors.grey)),
            ],
          ),
        ),
      );
    }

    final allGood = h.allGood;

    return Card(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(
          color: allGood
              ? Colors.green.withValues(alpha: 0.4)
              : Colors.orange.withValues(alpha: 00.4),
          width: 1.5,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            // Overall status banner
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(vertical: 10),
              decoration: BoxDecoration(
                color: allGood
                    ? Colors.green.withValues(alpha: 00.1)
                    : Colors.orange.withValues(alpha: 00.1),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    allGood ? Icons.check_circle : Icons.warning_amber,
                    color: allGood ? Colors.green : Colors.orange,
                    size: 20,
                  ),
                  const SizedBox(width: 8),
                  Text(
                    allGood ? 'All systems operational' : 'Issues detected',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      color: allGood ? Colors.green : Colors.orange,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),

            // Individual checks
            _CheckRow('VPN Service', h.vpnRunning),
            _CheckRow('DNS Server (port 5053)', h.dnsServerAlive),
            _CheckRow('Blocklist Loaded', h.blocklistLoaded,
                subtitle: h.domainCount > 0
                    ? '${(h.domainCount / 1000).toStringAsFixed(1)}k domains'
                    : null),
            _CheckRow('Upstream DNS (1.1.1.1)', h.upstreamReachable,
                subtitle: h.upstreamLatencyMs > 0
                    ? '${h.upstreamLatencyMs}ms'
                    : null),
            _CheckRow('Blocks doubleclick.net', h.adDomainBlocked),
            _CheckRow('Allows youtube.com', h.safeDomainAllowed),

            if (h.errorMessage != null) ...[
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: Colors.red.withValues(alpha: 00.08),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  h.errorMessage!,
                  style: const TextStyle(
                      fontSize: 11, color: Colors.red, fontFamily: 'monospace'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _CheckRow extends StatelessWidget {
  final String label;
  final bool pass;
  final String? subtitle;
  const _CheckRow(this.label, this.pass, {this.subtitle});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Icon(
            pass ? Icons.check_circle : Icons.cancel,
            size: 18,
            color: pass ? Colors.green : Colors.red,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: const TextStyle(fontSize: 13)),
                if (subtitle != null)
                  Text(subtitle!,
                      style: const TextStyle(fontSize: 11, color: Colors.grey)),
              ],
            ),
          ),
          Text(
            pass ? 'OK' : 'FAIL',
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.bold,
              color: pass ? Colors.green : Colors.red,
            ),
          ),
        ],
      ),
    );
  }
}

// ── Domain tester card ────────────────────────────────────────────────────────

class _DomainTesterCard extends StatelessWidget {
  final TextEditingController controller;
  final FocusNode focusNode;
  final DomainCheckResult? result;
  final bool loading;
  final Function(String) onCheck;
  final List<(String, bool)> testDomains;

  const _DomainTesterCard({
    required this.controller,
    required this.focusNode,
    required this.result,
    required this.loading,
    required this.onCheck,
    required this.testDomains,
  });

  @override
  Widget build(BuildContext context) {
    final r = result;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Input row
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: controller,
                    focusNode: focusNode,
                    decoration: const InputDecoration(
                      hintText: 'e.g. ads.example.com',
                      prefixIcon: Icon(Icons.dns_outlined, size: 18),
                      border: OutlineInputBorder(),
                      isDense: true,
                      contentPadding:
                          EdgeInsets.symmetric(vertical: 10, horizontal: 12),
                    ),
                    style:
                        const TextStyle(fontSize: 13, fontFamily: 'monospace'),
                    textInputAction: TextInputAction.search,
                    autocorrect: false,
                    onSubmitted: onCheck,
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton(
                  onPressed: loading ? null : () => onCheck(controller.text),
                  child: loading
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white))
                      : const Text('Check'),
                ),
              ],
            ),

            // Result
            if (r != null) ...[
              const SizedBox(height: 12),
              _ResultBanner(result: r),
            ],

            const SizedBox(height: 16),
            const Text('Quick tests',
                style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                    color: Colors.grey,
                    letterSpacing: 0.5)),
            const SizedBox(height: 8),

            // Pre-built test chips
            Wrap(
              spacing: 8,
              runSpacing: 6,
              children: testDomains.map((entry) {
                final (domain, shouldBlock) = entry;
                return GestureDetector(
                  onTap: () {
                    controller.text = domain;
                    onCheck(domain);
                  },
                  child: Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                    decoration: BoxDecoration(
                      color: shouldBlock
                          ? Colors.red.withValues(alpha: 00.08)
                          : Colors.green.withValues(alpha: 00.08),
                      border: Border.all(
                        color: shouldBlock
                            ? Colors.red.withValues(alpha: 00.3)
                            : Colors.green.withValues(alpha: 00.3),
                      ),
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          shouldBlock ? Icons.block : Icons.check,
                          size: 11,
                          color: shouldBlock ? Colors.red : Colors.green,
                        ),
                        const SizedBox(width: 4),
                        Text(
                          domain,
                          style: TextStyle(
                            fontSize: 11,
                            fontFamily: 'monospace',
                            color: shouldBlock ? Colors.red : Colors.green,
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              }).toList(),
            ),
          ],
        ),
      ),
    );
  }
}

class _ResultBanner extends StatelessWidget {
  final DomainCheckResult result;
  const _ResultBanner({required this.result});

  @override
  Widget build(BuildContext context) {
    final blocked = result.blocked;
    final color = blocked ? Colors.red : Colors.green;
    final icon = blocked ? Icons.block : Icons.check_circle;
    final label = blocked ? 'BLOCKED' : 'ALLOWED';

    return GestureDetector(
      onLongPress: () {
        Clipboard.setData(ClipboardData(text: result.domain));
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
              content: Text('Copied'), duration: Duration(seconds: 1)),
        );
      },
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 00.08),
          border: Border.all(color: color.withValues(alpha: 00.4)),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Row(
          children: [
            Icon(icon, color: color, size: 22),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(result.domain,
                      style: TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 13,
                          color: color,
                          fontWeight: FontWeight.w600)),
                  Text(label,
                      style: TextStyle(
                          fontSize: 11,
                          color: color,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 1)),
                ],
              ),
            ),
            if (result.error != null)
              const Icon(Icons.error_outline, color: Colors.orange, size: 18),
          ],
        ),
      ),
    );
  }
}

// ── Session stats card ────────────────────────────────────────────────────────

class _StatsCard extends StatelessWidget {
  final int total;
  final int blocked;
  final Map<String, int> topBlocked;
  const _StatsCard({
    required this.total,
    required this.blocked,
    required this.topBlocked,
  });

  @override
  Widget build(BuildContext context) {
    final allowed = total - blocked;
    final blockPct = total > 0 ? (blocked / total * 100) : 0.0;

    // Sort top blocked, take top 8
    final sorted = topBlocked.entries.toList()
      ..sort((a, b) => b.value.compareTo(a.value));
    final top = sorted.take(8).toList();

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Summary row
            Row(
              children: [
                _MiniStat('Total', '$total', Colors.grey),
                const SizedBox(width: 20),
                _MiniStat('Blocked', '$blocked', Colors.red),
                const SizedBox(width: 20),
                _MiniStat('Allowed', '$allowed', Colors.green),
                const Spacer(),
                Text(
                  '${blockPct.toStringAsFixed(1)}%',
                  style: const TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                      color: Colors.red),
                ),
              ],
            ),

            // Block rate bar
            if (total > 0) ...[
              const SizedBox(height: 12),
              ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: LinearProgressIndicator(
                  value: blockPct / 100,
                  minHeight: 6,
                  backgroundColor: Colors.green.withValues(alpha: 00.2),
                  valueColor: const AlwaysStoppedAnimation(Colors.red),
                ),
              ),
            ],

            // Top blocked domains
            if (top.isNotEmpty) ...[
              const SizedBox(height: 16),
              const Text('Top blocked this session',
                  style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.bold,
                      color: Colors.grey,
                      letterSpacing: 0.5)),
              const SizedBox(height: 8),
              ...top.map((e) => Padding(
                    padding: const EdgeInsets.only(bottom: 6),
                    child: Row(
                      children: [
                        const Icon(Icons.block, size: 12, color: Colors.red),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            e.key,
                            style: const TextStyle(
                                fontSize: 12, fontFamily: 'monospace'),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        Text(
                          '×${e.value}',
                          style: const TextStyle(
                              fontSize: 12,
                              color: Colors.red,
                              fontWeight: FontWeight.w600),
                        ),
                      ],
                    ),
                  )),
            ],

            if (total == 0)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: Center(
                  child: Text('No queries yet — start the VPN',
                      style: TextStyle(color: Colors.grey, fontSize: 13)),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _MiniStat extends StatelessWidget {
  final String label, value;
  final Color color;
  const _MiniStat(this.label, this.value, this.color);

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(value,
              style: TextStyle(
                  fontSize: 18, fontWeight: FontWeight.bold, color: color)),
          Text(label, style: const TextStyle(fontSize: 10, color: Colors.grey)),
        ],
      );
}
