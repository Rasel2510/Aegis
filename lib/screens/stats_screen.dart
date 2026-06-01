import 'package:flutter/material.dart';
import '../services/vpn_service.dart';

class StatsScreen extends StatefulWidget {
  const StatsScreen({super.key});
  @override
  State<StatsScreen> createState() => _StatsScreenState();
}

class _StatsScreenState extends State<StatsScreen> {
  List<Map<String, dynamic>> _daily = [];
  List<Map<String, dynamic>> _top = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    final daily = await VpnService.getStats();
    final top = await VpnService.getTopDomains();
    if (!mounted) return;
    setState(() {
      _daily = daily;
      _top = top;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Statistics',
            style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: _load)
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _daily.isEmpty
              ? _empty()
              : ListView(padding: const EdgeInsets.all(16), children: [
                  _sectionLabel('Last 7 Days'),
                  const SizedBox(height: 8),
                  _BarChart(data: _daily),
                  const SizedBox(height: 24),
                  _sectionLabel('Top Blocked Domains'),
                  const SizedBox(height: 8),
                  ..._top.map((e) => _TopDomainTile(
                      domain: e['domain'] as String,
                      count: e['count'] as int,
                      max: (_top.isNotEmpty ? _top.first['count'] as int : 1))),
                ]),
    );
  }

  Widget _sectionLabel(String t) => Text(t,
      style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.bold,
          letterSpacing: 0.5,
          color: Theme.of(context).colorScheme.primary));

  Widget _empty() => const Center(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
        Icon(Icons.bar_chart, size: 56, color: Colors.grey),
        SizedBox(height: 12),
        Text('No stats yet — start the VPN',
            style: TextStyle(color: Colors.grey)),
      ]));
}

class _BarChart extends StatelessWidget {
  final List<Map<String, dynamic>> data;
  const _BarChart({required this.data});

  @override
  Widget build(BuildContext context) {
    if (data.isEmpty) return const SizedBox.shrink();
    final maxBlocked =
        data.map((d) => d['blocked'] as int).reduce((a, b) => a > b ? a : b);

    return Card(
        child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(children: [
        SizedBox(
          height: 140,
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: data.reversed.take(7).toList().reversed.map((day) {
              final blocked = day['blocked'] as int;
              final frac = maxBlocked > 0 ? blocked / maxBlocked : 0.0;
              final label = (day['date'] as String).substring(5); // MM-DD
              return Expanded(
                  child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 2),
                child:
                    Column(mainAxisAlignment: MainAxisAlignment.end, children: [
                  if (blocked > 0)
                    Text(
                        blocked > 999
                            ? '${(blocked / 1000).toStringAsFixed(1)}k'
                            : '$blocked',
                        style: const TextStyle(fontSize: 8, color: Colors.red)),
                  const SizedBox(height: 2),
                  Container(
                    height: (frac * 100).clamp(4.0, 100.0),
                    decoration: BoxDecoration(
                        color: Colors.red.withValues(alpha: 00.7),
                        borderRadius: BorderRadius.circular(3)),
                  ),
                  const SizedBox(height: 4),
                  Text(label,
                      style: const TextStyle(fontSize: 9, color: Colors.grey)),
                ]),
              ));
            }).toList(),
          ),
        ),
        const SizedBox(height: 12),
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          Container(
              width: 10,
              height: 10,
              decoration: BoxDecoration(
                  color: Colors.red.withValues(alpha: 00.7),
                  borderRadius: BorderRadius.circular(2))),
          const SizedBox(width: 6),
          const Text('Blocked per day',
              style: TextStyle(fontSize: 11, color: Colors.grey)),
        ]),
      ]),
    ));
  }
}

class _TopDomainTile extends StatelessWidget {
  final String domain;
  final int count, max;
  const _TopDomainTile(
      {required this.domain, required this.count, required this.max});

  @override
  Widget build(BuildContext context) {
    final frac = max > 0 ? count / max : 0.0;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(children: [
        const Icon(Icons.block, size: 12, color: Colors.red),
        const SizedBox(width: 8),
        Expanded(
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(domain,
              style: const TextStyle(fontSize: 12, fontFamily: 'monospace'),
              overflow: TextOverflow.ellipsis),
          const SizedBox(height: 3),
          ClipRRect(
            borderRadius: BorderRadius.circular(2),
            child: LinearProgressIndicator(
              value: frac,
              minHeight: 3,
              backgroundColor: Colors.red.withValues(alpha: 00.1),
              valueColor: const AlwaysStoppedAnimation(Colors.red),
            ),
          ),
        ])),
        const SizedBox(width: 8),
        Text('$count',
            style: const TextStyle(
                fontSize: 12, color: Colors.red, fontWeight: FontWeight.bold)),
      ]),
    );
  }
}
