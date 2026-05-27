import 'package:flutter/material.dart';
import '../services/vpn_service.dart';

class ExclusionsScreen extends StatefulWidget {
  const ExclusionsScreen({super.key});
  @override State<ExclusionsScreen> createState() => _ExclusionsScreenState();
}

class _ExclusionsScreenState extends State<ExclusionsScreen> {
  List<String> _excluded = [];
  final _ctrl = TextEditingController();

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    final list = await VpnService.getExclusions();
    if (!mounted) return;
    setState(() => _excluded = list);
  }

  Future<void> _add() async {
    final pkg = _ctrl.text.trim();
    if (pkg.isEmpty) return;
    await VpnService.addExclusion(pkg);
    _ctrl.clear();
    _load();
  }

  Future<void> _remove(String pkg) async {
    await VpnService.removeExclusion(pkg);
    _load();
  }

  @override
  void dispose() { _ctrl.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('App Exclusions', style: TextStyle(fontWeight: FontWeight.bold)),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          // Info card
          Card(child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: const [
              Row(children: [
                Icon(Icons.info_outline, size: 16, color: Colors.orange),
                SizedBox(width: 8),
                Text('About Exclusions', style: TextStyle(fontWeight: FontWeight.bold)),
              ]),
              SizedBox(height: 8),
              Text(
                'Apps in this list bypass HTTPS filtering entirely. '
                'Add banking apps or any app that stops working after enabling '
                'HTTPS filtering.\n\n'
                'DNS blocking still applies to excluded apps.',
                style: TextStyle(fontSize: 12, color: Colors.grey, height: 1.5),
              ),
            ]),
          )),
          const SizedBox(height: 16),

          // Add row
          Row(children: [
            Expanded(child: TextField(
              controller: _ctrl,
              decoration: const InputDecoration(
                hintText: 'com.your.banking.app',
                labelText: 'Package name',
                border: OutlineInputBorder(), isDense: true,
                contentPadding: EdgeInsets.symmetric(vertical: 10, horizontal: 12),
              ),
              style: const TextStyle(fontSize: 13, fontFamily: 'monospace'),
              autocorrect: false,
              onSubmitted: (_) => _add(),
            )),
            const SizedBox(width: 8),
            FilledButton(onPressed: _add, child: const Text('Add')),
          ]),
          const SizedBox(height: 16),

          // List
          Text('${_excluded.length} excluded apps',
              style: const TextStyle(fontSize: 11, color: Colors.grey, letterSpacing: 0.5)),
          const SizedBox(height: 8),
          Expanded(child: ListView.separated(
            itemCount: _excluded.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (ctx, i) {
              final pkg = _excluded[i];
              final isDefault = pkg.startsWith('android') ||
                  pkg.startsWith('com.android') || pkg.startsWith('com.google.android.gms');
              return ListTile(
                dense: true,
                leading: Icon(Icons.android, size: 18,
                    color: isDefault ? Colors.grey : Colors.blue),
                title: Text(pkg,
                    style: const TextStyle(fontSize: 12, fontFamily: 'monospace')),
                subtitle: isDefault
                    ? const Text('System default', style: TextStyle(fontSize: 10))
                    : null,
                trailing: isDefault ? null : IconButton(
                  icon: const Icon(Icons.remove_circle_outline,
                      size: 18, color: Colors.red),
                  onPressed: () => _remove(pkg),
                ),
              );
            },
          )),
        ]),
      ),
    );
  }
}
