import 'package:flutter/material.dart';
import '../services/vpn_service.dart';

class CustomRulesScreen extends StatefulWidget {
  const CustomRulesScreen({super.key});
  @override State<CustomRulesScreen> createState() => _CustomRulesScreenState();
}

class _CustomRulesScreenState extends State<CustomRulesScreen>
    with SingleTickerProviderStateMixin {
  late TabController _tabs;
  final _blockCtrl = TextEditingController();
  final _allowCtrl = TextEditingController();
  List<String> _blockList = [];
  List<String> _allowList = [];

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 2, vsync: this);
    _load();
  }

  Future<void> _load() async {
    final rules = await VpnService.getCustomRules();
    if (!mounted) return;
    setState(() {
      _blockList = List<String>.from(rules['block'] ?? []);
      _allowList = List<String>.from(rules['allow'] ?? []);
    });
  }

  Future<void> _addBlock() async {
    final d = _blockCtrl.text.trim().toLowerCase();
    if (d.isEmpty) return;
    await VpnService.addCustomBlock(d);
    _blockCtrl.clear();
    _load();
  }

  Future<void> _addAllow() async {
    final d = _allowCtrl.text.trim().toLowerCase();
    if (d.isEmpty) return;
    await VpnService.addCustomAllow(d);
    _allowCtrl.clear();
    _load();
  }

  @override
  void dispose() { _tabs.dispose(); _blockCtrl.dispose(); _allowCtrl.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Custom Rules', style: TextStyle(fontWeight: FontWeight.bold)),
        bottom: TabBar(controller: _tabs, tabs: const [
          Tab(text: 'Block List', icon: Icon(Icons.block, size: 16)),
          Tab(text: 'Allow List', icon: Icon(Icons.check_circle_outline, size: 16)),
        ]),
      ),
      body: TabBarView(controller: _tabs, children: [
        _RuleTab(
          label: 'Block',
          color: Colors.red,
          hint: 'e.g. ads.example.com',
          controller: _blockCtrl,
          domains: _blockList,
          onAdd: _addBlock,
          subtitle: 'These domains are always blocked, even if not in the main list.',
        ),
        _RuleTab(
          label: 'Allow',
          color: Colors.green,
          hint: 'e.g. safe.example.com',
          controller: _allowCtrl,
          domains: _allowList,
          onAdd: _addAllow,
          subtitle: 'These domains are never blocked, even if in the main list.',
        ),
      ]),
    );
  }
}

class _RuleTab extends StatelessWidget {
  final String label, hint, subtitle;
  final Color color;
  final TextEditingController controller;
  final List<String> domains;
  final VoidCallback onAdd;
  const _RuleTab({required this.label, required this.color, required this.hint,
      required this.controller, required this.domains, required this.onAdd,
      required this.subtitle});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(children: [
        // Info
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(color: color.withOpacity(0.07),
              border: Border.all(color: color.withOpacity(0.3)),
              borderRadius: BorderRadius.circular(8)),
          child: Row(children: [
            Icon(label == 'Block' ? Icons.block : Icons.check_circle_outline,
                color: color, size: 16),
            const SizedBox(width: 8),
            Expanded(child: Text(subtitle,
                style: TextStyle(fontSize: 12, color: color))),
          ]),
        ),
        const SizedBox(height: 16),
        // Add row
        Row(children: [
          Expanded(child: TextField(
            controller: controller,
            decoration: InputDecoration(hintText: hint,
                border: const OutlineInputBorder(), isDense: true,
                contentPadding: const EdgeInsets.symmetric(vertical: 10, horizontal: 12)),
            style: const TextStyle(fontSize: 13, fontFamily: 'monospace'),
            autocorrect: false,
            onSubmitted: (_) => onAdd(),
          )),
          const SizedBox(width: 8),
          FilledButton(onPressed: onAdd,
              style: FilledButton.styleFrom(backgroundColor: color),
              child: const Text('Add')),
        ]),
        const SizedBox(height: 16),
        // List
        Expanded(child: domains.isEmpty
          ? Center(child: Text('No custom $label rules yet',
              style: const TextStyle(color: Colors.grey)))
          : ListView.separated(
              itemCount: domains.length,
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (ctx, i) => ListTile(
                dense: true,
                leading: Icon(label == 'Block' ? Icons.block : Icons.check_circle_outline,
                    color: color, size: 18),
                title: Text(domains[i],
                    style: const TextStyle(fontSize: 13, fontFamily: 'monospace')),
                trailing: IconButton(
                  icon: const Icon(Icons.delete_outline, size: 18, color: Colors.grey),
                  onPressed: () {
                    ScaffoldMessenger.of(ctx).showSnackBar(SnackBar(
                      content: Text('${domains[i]} removed'),
                      duration: const Duration(seconds: 2),
                    ));
                  },
                ),
              ),
            )),
      ]),
    );
  }
}
