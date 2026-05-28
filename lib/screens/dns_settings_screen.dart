import 'package:flutter/material.dart';
import '../services/vpn_service.dart';

class DnsSettingsScreen extends StatefulWidget {
  const DnsSettingsScreen({super.key});
  @override
  State<DnsSettingsScreen> createState() => _DnsSettingsScreenState();
}

class _DnsSettingsScreenState extends State<DnsSettingsScreen> {
  String? _selectedUrl;
  final _customCtrl = TextEditingController();
  bool _httpsEnabled = false;
  bool _caInstalled = false;

  static const _providers = [
    (
      label: 'Disabled (plain UDP)',
      url: null,
      desc: 'Direct UDP to 1.1.1.1. Fast, but ISP can see queries.'
    ),
    (
      label: 'Cloudflare (1.1.1.1)',
      url: 'https://1.1.1.1/dns-query',
      desc: 'Privacy-focused. Fast. No logging.'
    ),
    (
      label: 'Google (8.8.8.8)',
      url: 'https://8.8.8.8/dns-query',
      desc: 'Reliable. Google logs queries for 48h.'
    ),
    (
      label: 'AdGuard DNS',
      url: 'https://dns.adguard.com/dns-query',
      desc: 'Blocks ads at DNS level in addition to Aegis.'
    ),
    (
      label: 'NextDNS',
      url: 'https://dns.nextdns.io/dns-query',
      desc: 'Configurable. Free tier available.'
    ),
  ];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final url = await VpnService.getDohUrl();
    final https = await VpnService.getHttpsFiltering();
    final caInstalled = await VpnService.isCaInstalled();
    if (!mounted) return;
    setState(() {
      _selectedUrl = url;
      _httpsEnabled = https;
      _caInstalled = caInstalled;
    });
  }

  Future<void> _save() async {
    final url = !_providers.any((p) => p.url == _selectedUrl)
        ? _customCtrl.text.trim().nullIfEmpty()
        : _selectedUrl;
    await VpnService.setDohUrl(url);
    await VpnService.setHttpsFiltering(_httpsEnabled);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
      content: Text('Settings saved — restart VPN to apply'),
      duration: Duration(seconds: 3),
    ));
  }

  bool get _isCustomSelected =>
      !_providers.any((p) => p.url == _selectedUrl) && _selectedUrl != null;

  @override
  void dispose() {
    _customCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('DNS Settings',
            style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [TextButton(onPressed: _save, child: const Text('Save'))],
      ),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        // ── HTTPS Filtering ───────────────────────────────────────────────────
        _sectionHeader('HTTPS Filtering', Icons.https),
        const SizedBox(height: 8),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(
                    child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                      const Text('Enable HTTPS Filtering',
                          style: TextStyle(fontWeight: FontWeight.bold)),
                      const SizedBox(height: 4),
                      Text('Blocks encrypted ads (requires CA cert)',
                          style: TextStyle(
                              fontSize: 12, color: Colors.grey.shade600)),
                    ])),
                Switch(
                    value: _httpsEnabled,
                    onChanged: (v) => setState(() => _httpsEnabled = v)),
              ]),
              if (_httpsEnabled && !_caInstalled) ...[
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                      color: Colors.orange.withValues(alpha: 0.1),
                      border: Border.all(
                          color: Colors.orange.withValues(alpha: 0.4)),
                      borderRadius: BorderRadius.circular(8)),
                  child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Row(children: [
                          Icon(Icons.warning_amber,
                              color: Colors.orange, size: 16),
                          SizedBox(width: 6),
                          Text('CA Certificate Required',
                              style: TextStyle(
                                  fontWeight: FontWeight.bold,
                                  color: Colors.orange,
                                  fontSize: 13)),
                        ]),
                        const SizedBox(height: 8),
                        const Text(
                            'To filter HTTPS traffic install Aegis\'s CA certificate:\n'
                            '1. Tap "Export & Install" below\n'
                            '2. Open the downloaded file\n'
                            '3. Settings → Security → Install Certificate → CA Certificate',
                            style: TextStyle(fontSize: 12, height: 1.6)),
                        const SizedBox(height: 10),
                        FilledButton.icon(
                          onPressed: () async {
                            final path = await VpnService.exportCaCert();
                            if (path != null && context.mounted) {
                              ScaffoldMessenger.of(context)
                                  .showSnackBar(SnackBar(
                                content: Text('Cert saved to: $path'),
                                duration: const Duration(seconds: 4),
                              ));
                            }
                          },
                          icon: const Icon(Icons.download, size: 16),
                          label: const Text('Export & Install'),
                          style: FilledButton.styleFrom(
                              backgroundColor: Colors.orange),
                        ),
                      ]),
                ),
              ],
              if (_httpsEnabled && _caInstalled) ...[
                const SizedBox(height: 8),
                const Row(children: [
                  Icon(Icons.check_circle, color: Colors.green, size: 16),
                  SizedBox(width: 6),
                  Text('CA certificate installed',
                      style: TextStyle(color: Colors.green, fontSize: 12)),
                ]),
              ],
            ]),
          ),
        ),

        const SizedBox(height: 24),

        // ── DNS-over-HTTPS ────────────────────────────────────────────────────
        _sectionHeader('DNS-over-HTTPS', Icons.lock_outlined),
        const SizedBox(height: 8),
        Card(
          child: Column(children: [
            // Known providers — plain InkWell+Radio, avoids deprecated RadioListTile
            ..._providers.map((p) => _ProviderTile(
                  label: p.label,
                  desc: p.desc,
                  selected: _selectedUrl == p.url,
                  onTap: () => setState(() => _selectedUrl = p.url),
                )),

            // Custom option
            _ProviderTile(
              label: 'Custom',
              desc: 'Enter your own DoH endpoint',
              selected: _isCustomSelected,
              onTap: () => setState(() => _selectedUrl = 'custom'),
            ),

            // Custom URL text field
            if (_isCustomSelected)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
                child: TextField(
                  controller: _customCtrl,
                  decoration: const InputDecoration(
                    hintText: 'https://your-doh-server/dns-query',
                    border: OutlineInputBorder(),
                    isDense: true,
                    contentPadding:
                        EdgeInsets.symmetric(vertical: 10, horizontal: 12),
                  ),
                  style: const TextStyle(fontSize: 12, fontFamily: 'monospace'),
                  autocorrect: false,
                ),
              ),
          ]),
        ),
      ]),
    );
  }

  Widget _sectionHeader(String title, IconData icon) => Row(children: [
        Icon(icon, size: 16, color: Theme.of(context).colorScheme.primary),
        const SizedBox(width: 8),
        Text(title,
            style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.bold,
                letterSpacing: 0.5,
                color: Theme.of(context).colorScheme.primary)),
      ]);
}

// ── _ProviderTile — replaces deprecated RadioListTile ─────────────────────────

class _ProviderTile extends StatelessWidget {
  final String label, desc;
  final bool selected;
  final VoidCallback onTap;

  const _ProviderTile({
    required this.label,
    required this.desc,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
        child: Row(children: [
          RadioGroup<bool>(
            groupValue: selected,
            onChanged: (_) => onTap(),
            child: Radio<bool>(
              value: true,
              materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: const TextStyle(fontSize: 13)),
                Text(desc,
                    style: const TextStyle(fontSize: 11, color: Colors.grey)),
              ],
            ),
          ),
        ]),
      ),
    );
  }
}

extension on String {
  String? nullIfEmpty() => isEmpty ? null : this;
}
