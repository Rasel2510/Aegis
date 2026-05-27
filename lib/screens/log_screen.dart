import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/vpn_service.dart';

/// DAY 3 — LogScreen
///
/// Real-time DNS query log. Listens directly to VpnService.logStream
/// so updates are instant without going through the provider poll cycle.
class LogScreen extends StatefulWidget {
  const LogScreen({super.key});

  @override
  State<LogScreen> createState() => _LogScreenState();
}

class _LogScreenState extends State<LogScreen> {
  final List<LogEntry> _entries = [];
  StreamSubscription<LogEntry>? _sub;
  final _scrollController = ScrollController();

  bool _autoScroll = true;
  bool _showBlocked = true;
  bool _showAllowed = true;
  String _filter = '';

  static const _maxEntries = 500;

  @override
  void initState() {
    super.initState();
    _loadSnapshot();
    _sub = VpnService.logStream.listen(_onEntry);
  }

  Future<void> _loadSnapshot() async {
    final snap = await VpnService.getLogSnapshot();
    if (!mounted) return;
    setState(() {
      _entries.clear();
      _entries.addAll(snap);
    });
  }

  void _onEntry(LogEntry entry) {
    if (!mounted) return;
    setState(() {
      _entries.add(entry);
      if (_entries.length > _maxEntries) _entries.removeAt(0);
    });
    if (_autoScroll) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scrollController.hasClients) {
          _scrollController.animateTo(
            _scrollController.position.maxScrollExtent,
            duration: const Duration(milliseconds: 150),
            curve: Curves.easeOut,
          );
        }
      });
    }
  }

  List<LogEntry> get _filtered {
    return _entries.where((e) {
      if (e.blocked && !_showBlocked) return false;
      if (!e.blocked && !_showAllowed) return false;
      if (_filter.isNotEmpty && !e.domain.contains(_filter.toLowerCase()))
        return false;
      return true;
    }).toList();
  }

  @override
  void dispose() {
    _sub?.cancel();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDark = theme.brightness == Brightness.dark;
    final filtered = _filtered;

    final blockedCount = _entries.where((e) => e.blocked).length;
    final allowedCount = _entries.length - blockedCount;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Query Log',
            style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [
          // Auto-scroll toggle
          IconButton(
            icon: Icon(
              _autoScroll ? Icons.vertical_align_bottom : Icons.pause,
              color: _autoScroll ? theme.colorScheme.primary : Colors.grey,
            ),
            tooltip: _autoScroll ? 'Auto-scroll on' : 'Auto-scroll off',
            onPressed: () => setState(() => _autoScroll = !_autoScroll),
          ),
          // Clear
          IconButton(
            icon: const Icon(Icons.delete_sweep_outlined),
            tooltip: 'Clear log',
            onPressed: () => setState(() => _entries.clear()),
          ),
        ],
      ),
      body: Column(
        children: [
          // ── Summary bar ────────────────────────────────────────────────
          _SummaryBar(
            total: _entries.length,
            blocked: blockedCount,
            allowed: allowedCount,
          ),

          // ── Filter row ─────────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
            child: Row(
              children: [
                // Search field
                Expanded(
                  child: SizedBox(
                    height: 38,
                    child: TextField(
                      style: const TextStyle(fontSize: 13),
                      decoration: InputDecoration(
                        hintText: 'Filter domains...',
                        hintStyle: const TextStyle(fontSize: 13),
                        prefixIcon: const Icon(Icons.search, size: 18),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(20),
                        ),
                        contentPadding: const EdgeInsets.symmetric(
                            vertical: 0, horizontal: 12),
                        isDense: true,
                      ),
                      onChanged: (v) => setState(() => _filter = v),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                // Blocked toggle
                _FilterChip(
                  label: 'Blocked',
                  color: Colors.red,
                  active: _showBlocked,
                  onTap: () => setState(() => _showBlocked = !_showBlocked),
                ),
                const SizedBox(width: 6),
                // Allowed toggle
                _FilterChip(
                  label: 'Allowed',
                  color: Colors.green,
                  active: _showAllowed,
                  onTap: () => setState(() => _showAllowed = !_showAllowed),
                ),
              ],
            ),
          ),

          const Divider(height: 1),

          // ── Log list ───────────────────────────────────────────────────
          Expanded(
            child: filtered.isEmpty
                ? _EmptyState(
                    isFiltered:
                        _filter.isNotEmpty || !_showBlocked || !_showAllowed)
                : ListView.builder(
                    controller: _scrollController,
                    itemCount: filtered.length,
                    itemExtent: 52,
                    itemBuilder: (ctx, i) {
                      final e = filtered[i];
                      return _LogTile(
                        entry: e,
                        isDark: isDark,
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

// ── Summary bar ───────────────────────────────────────────────────────────────

class _SummaryBar extends StatelessWidget {
  final int total, blocked, allowed;
  const _SummaryBar({
    required this.total,
    required this.blocked,
    required this.allowed,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Theme.of(context)
          .colorScheme
          .surfaceContainerHighest
          .withValues(alpha: 0.4),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          _Stat(label: 'Total', value: '$total', color: Colors.grey),
          const SizedBox(width: 20),
          _Stat(label: 'Blocked', value: '$blocked', color: Colors.red),
          const SizedBox(width: 20),
          _Stat(label: 'Allowed', value: '$allowed', color: Colors.green),
          const Spacer(),
          if (total > 0)
            Text(
              '${(blocked / total * 100).toStringAsFixed(1)}% blocked',
              style: const TextStyle(fontSize: 11, color: Colors.grey),
            ),
        ],
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  final String label, value;
  final Color color;
  const _Stat({required this.label, required this.value, required this.color});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(value,
            style: TextStyle(
                fontSize: 16, fontWeight: FontWeight.bold, color: color)),
        Text(label, style: const TextStyle(fontSize: 10, color: Colors.grey)),
      ],
    );
  }
}

// ── Filter chip ───────────────────────────────────────────────────────────────

class _FilterChip extends StatelessWidget {
  final String label;
  final Color color;
  final bool active;
  final VoidCallback onTap;
  const _FilterChip({
    required this.label,
    required this.color,
    required this.active,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: active ? color.withValues(alpha: 0.15) : Colors.transparent,
          border: Border.all(
              color: active ? color : Colors.grey.withValues(alpha: 0.4)),
          borderRadius: BorderRadius.circular(16),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 11,
            color: active ? color : Colors.grey,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}

// ── Log tile ──────────────────────────────────────────────────────────────────

class _LogTile extends StatelessWidget {
  final LogEntry entry;
  final bool isDark;
  const _LogTile({required this.entry, required this.isDark});

  @override
  Widget build(BuildContext context) {
    final blocked = entry.blocked;
    final color = blocked ? Colors.red : Colors.green;
    final bg = blocked
        ? Colors.red.withValues(alpha: isDark ? 0.07 : 0.04)
        : Colors.transparent;

    final hh = entry.time.hour.toString().padLeft(2, '0');
    final mm = entry.time.minute.toString().padLeft(2, '0');
    final ss = entry.time.second.toString().padLeft(2, '0');

    return GestureDetector(
      onLongPress: () {
        Clipboard.setData(ClipboardData(text: entry.domain));
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Copied: ${entry.domain}'),
            duration: const Duration(seconds: 1),
          ),
        );
      },
      child: Container(
        color: bg,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Row(
          children: [
            // Indicator dot
            Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(color: color, shape: BoxShape.circle),
            ),
            const SizedBox(width: 10),
            // Domain
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    entry.domain,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                      color: blocked
                          ? (isDark ? Colors.red.shade300 : Colors.red.shade700)
                          : null,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  Text(
                    blocked ? 'BLOCKED' : 'ALLOWED',
                    style: TextStyle(
                      fontSize: 10,
                      color: color,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.5,
                    ),
                  ),
                ],
              ),
            ),
            // Timestamp
            Text(
              '$hh:$mm:$ss',
              style: const TextStyle(
                  fontSize: 11, color: Colors.grey, fontFamily: 'monospace'),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Empty state ───────────────────────────────────────────────────────────────

class _EmptyState extends StatelessWidget {
  final bool isFiltered;
  const _EmptyState({required this.isFiltered});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            isFiltered ? Icons.search_off : Icons.dns_outlined,
            size: 56,
            color: Colors.grey.withValues(alpha: 0.4),
          ),
          const SizedBox(height: 12),
          Text(
            isFiltered ? 'No entries match your filter' : 'No queries yet',
            style: const TextStyle(color: Colors.grey, fontSize: 14),
          ),
          if (!isFiltered) ...[
            const SizedBox(height: 6),
            const Text(
              'Start the VPN to see live DNS traffic',
              style: TextStyle(color: Colors.grey, fontSize: 12),
            ),
          ],
        ],
      ),
    );
  }
}
