import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/ad_block_provider.dart';
import 'log_screen.dart';
import 'dns_settings_screen.dart';
import 'custom_rules_screen.dart';
import 'exclusions_screen.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<AdBlockProvider>();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Aegis', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 26)),
        actions: [
          IconButton(
            icon: Icon(provider.darkMode ? Icons.light_mode : Icons.dark_mode),
            onPressed: () => provider.toggleDarkMode(!provider.darkMode),
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.tune),
            tooltip: 'Settings',
            onSelected: (route) {
              Navigator.push(context, MaterialPageRoute(builder: (_) => switch (route) {
                'dns'       => const DnsSettingsScreen(),
                'rules'     => const CustomRulesScreen(),
                'exclusions'=> const ExclusionsScreen(),
                _           => const DnsSettingsScreen(),
              }));
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'dns',
                child: ListTile(dense: true, leading: Icon(Icons.lock_outlined),
                    title: Text('DNS & HTTPS'))),
              PopupMenuItem(value: 'rules',
                child: ListTile(dense: true, leading: Icon(Icons.rule),
                    title: Text('Custom Rules'))),
              PopupMenuItem(value: 'exclusions',
                child: ListTile(dense: true, leading: Icon(Icons.android),
                    title: Text('App Exclusions'))),
            ],
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            children: [
              const SizedBox(height: 16),
              _BigShieldButton(provider: provider),
              const SizedBox(height: 28),
              _StatsRow(provider: provider),
              const SizedBox(height: 12),
              _LiveLogShortcut(provider: provider),
              const SizedBox(height: 8),
              _BlocklistStatusCard(provider: provider),
              const SizedBox(height: 16),
              _WhatIsBlockedCard(isBlocking: provider.isBlocking),
              const SizedBox(height: 16),
              _HowItWorksCard(),
            ],
          ),
        ),
      ),
    );
  }
}

class _BigShieldButton extends StatelessWidget {
  final AdBlockProvider provider;
  const _BigShieldButton({required this.provider});

  @override
  Widget build(BuildContext context) {
    final isOn = provider.isBlocking;
    final isLoading = provider.isUpdating;
    final color = isOn ? const Color(0xFF6C63FF) : Colors.grey;

    return Column(
      children: [
        GestureDetector(
          onTap: isLoading ? null : () async {
            final granted = await provider.toggleBlocking();
            if (!granted && context.mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                  content: Text('VPN permission denied. Please allow to enable Aegis.'),
                  backgroundColor: Colors.red,
                ),
              );
            }
          },
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 400),
            width: 190,
            height: 190,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: color.withOpacity(0.08),
              border: Border.all(color: color, width: 4),
              boxShadow: isOn
                  ? [BoxShadow(color: color.withOpacity(0.35), blurRadius: 50, spreadRadius: 12)]
                  : [],
            ),
            child: isLoading
                ? Center(child: CircularProgressIndicator(color: color))
                : Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(isOn ? Icons.shield : Icons.shield_outlined, size: 64, color: color),
                      const SizedBox(height: 8),
                      Text(
                        isOn ? 'ON' : 'OFF',
                        style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: color),
                      ),
                      Text(
                        isOn ? 'Tap to stop' : 'Tap to start',
                        style: TextStyle(fontSize: 12, color: color.withOpacity(0.7)),
                      ),
                    ],
                  ),
          ),
        ),
        const SizedBox(height: 16),
        AnimatedContainer(
          duration: const Duration(milliseconds: 300),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
          decoration: BoxDecoration(
            color: isOn ? Colors.green.withOpacity(0.1) : Colors.red.withOpacity(0.1),
            borderRadius: BorderRadius.circular(20),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(isOn ? Icons.check_circle : Icons.cancel,
                  size: 16, color: isOn ? Colors.green : Colors.red),
              const SizedBox(width: 6),
              Text(
                isOn ? 'VPN Active — Ads are being blocked' : 'VPN Off — Ads not blocked',
                style: TextStyle(
                  fontSize: 13,
                  color: isOn ? Colors.green : Colors.red,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _StatsRow extends StatelessWidget {
  final AdBlockProvider provider;
  const _StatsRow({required this.provider});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: _StatCard(
            value: '${provider.adsBlocked}',
            label: 'Ads Blocked',
            icon: Icons.block,
            color: Colors.red,
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: _StatCard(
            value: provider.domainCountFormatted,
            label: 'Filter Rules',
            icon: Icons.filter_list,
            color: const Color(0xFF6C63FF),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: _StatCard(
            value: provider.lastUpdateFormatted,
            label: 'Updated',
            icon: Icons.update,
            color: Colors.teal,
          ),
        ),
      ],
    );
  }
}

class _StatCard extends StatelessWidget {
  final String value;
  final String label;
  final IconData icon;
  final Color color;
  const _StatCard({required this.value, required this.label, required this.icon, required this.color});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          children: [
            Icon(icon, color: color, size: 20),
            const SizedBox(height: 6),
            Text(value,
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16, color: color),
                maxLines: 1,
                overflow: TextOverflow.ellipsis),
            Text(label,
                style: const TextStyle(fontSize: 11, color: Colors.grey),
                textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}

class _BlocklistStatusCard extends StatelessWidget {
  final AdBlockProvider provider;
  const _BlocklistStatusCard({required this.provider});

  @override
  Widget build(BuildContext context) {
    final domainCount = provider.domainCount;
    final isLoaded = domainCount > 0;
    final isFresh = provider.lastUpdate != null;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: const Color(0xFF6C63FF).withOpacity(0.1),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Icon(Icons.download_done, color: Color(0xFF6C63FF), size: 20),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('Auto-Updated Blocklist',
                          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                      Text(
                        isLoaded
                            ? '$domainCount domains loaded'
                            : 'Downloading blocklist...',
                        style: TextStyle(
                          fontSize: 12,
                          color: isLoaded ? Colors.green : Colors.orange,
                        ),
                      ),
                    ],
                  ),
                ),
                if (!isLoaded)
                  const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                if (isLoaded)
                  const Icon(Icons.check_circle, color: Colors.green, size: 20),
              ],
            ),
            const SizedBox(height: 14),
            const Divider(height: 1),
            const SizedBox(height: 14),
            _SourceRow(
              name: 'Steven Black Unified',
              domains: '~70,000',
              color: Colors.purple,
            ),
            const SizedBox(height: 6),
            _SourceRow(
              name: 'AdAway Default',
              domains: '~15,000',
              color: Colors.blue,
            ),
            const SizedBox(height: 6),
            _SourceRow(
              name: 'URLhaus Malware',
              domains: '~10,000',
              color: Colors.red,
            ),
            const SizedBox(height: 6),
            _SourceRow(
              name: 'Lightswitch05 Tracking',
              domains: '~8,000',
              color: Colors.orange,
            ),
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: Colors.green.withOpacity(0.08),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                children: [
                  const Icon(Icons.autorenew, size: 14, color: Colors.green),
                  const SizedBox(width: 6),
                  Text(
                    isFresh
                        ? 'Last refreshed: ${provider.lastUpdateFormatted} • Updates every 24h'
                        : 'Will auto-download on first launch',
                    style: const TextStyle(fontSize: 11, color: Colors.green),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SourceRow extends StatelessWidget {
  final String name;
  final String domains;
  final Color color;
  const _SourceRow({required this.name, required this.domains, required this.color});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(width: 8, height: 8, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
        const SizedBox(width: 8),
        Expanded(child: Text(name, style: const TextStyle(fontSize: 12))),
        Text(domains, style: TextStyle(fontSize: 12, color: color, fontWeight: FontWeight.w600)),
      ],
    );
  }
}

class _WhatIsBlockedCard extends StatelessWidget {
  final bool isBlocking;
  const _WhatIsBlockedCard({required this.isBlocking});

  @override
  Widget build(BuildContext context) {
    final items = [
      ('YouTube ad servers', Icons.play_circle, Colors.red),
      ('Banner & display ads', Icons.web, Colors.orange),
      ('Pop-ups & redirects', Icons.open_in_new, Colors.purple),
      ('Trackers & analytics', Icons.track_changes, Colors.blue),
      ('Malware domains', Icons.bug_report, Colors.red),
      ('Crypto miners', Icons.currency_bitcoin, Colors.amber),
    ];

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.block, color: Color(0xFF6C63FF)),
                const SizedBox(width: 8),
                Text('What Aegis Blocks',
                    style: Theme.of(context)
                        .textTheme
                        .titleMedium
                        ?.copyWith(fontWeight: FontWeight.bold)),
              ],
            ),
            const SizedBox(height: 12),
            ...items.map((item) => Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Row(
                    children: [
                      Icon(item.$2, size: 16, color: item.$3),
                      const SizedBox(width: 8),
                      Text(item.$1, style: const TextStyle(fontSize: 13)),
                      const Spacer(),
                      Icon(
                        isBlocking ? Icons.check_circle : Icons.radio_button_unchecked,
                        size: 16,
                        color: isBlocking ? Colors.green : Colors.grey,
                      ),
                    ],
                  ),
                )),
          ],
        ),
      ),
    );
  }
}

class _HowItWorksCard extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.help_outline, color: Color(0xFF6C63FF)),
                const SizedBox(width: 8),
                Text('How Aegis Works',
                    style: Theme.of(context)
                        .textTheme
                        .titleMedium
                        ?.copyWith(fontWeight: FontWeight.bold)),
              ],
            ),
            const SizedBox(height: 10),
            const Text(
              'Aegis runs a local DNS server on your phone. '
              'When any app tries to contact an ad server, Aegis returns "not found" — '
              'so the ad never loads in any app.\n\n'
              'The blocklist is downloaded from trusted sources and refreshed every 24 hours automatically. '
              'No data ever leaves your phone — everything runs on-device.',
              style: TextStyle(fontSize: 13, color: Colors.grey, height: 1.5),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Live log shortcut ────────────────────────────────────────────────────────

class _LiveLogShortcut extends StatelessWidget {
  final AdBlockProvider provider;
  const _LiveLogShortcut({required this.provider});

  @override
  Widget build(BuildContext context) {
    final recentBlocked = provider.log.reversed
        .where((e) => e.blocked)
        .take(3)
        .toList();

    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () {
          // Switch to the Log tab via the bottom nav
          // We do this by looking up the NavigationBar state via the scaffold
          Navigator.push(
            context,
            MaterialPageRoute(builder: (_) => const LogScreen()),
          );
        },
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: Colors.red.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Icon(Icons.dns_outlined,
                    color: Colors.red, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Live Query Log',
                        style: TextStyle(
                            fontWeight: FontWeight.bold, fontSize: 14)),
                    if (recentBlocked.isEmpty)
                      Text(
                        provider.isBlocking
                            ? 'Waiting for DNS queries...'
                            : 'Start VPN to see activity',
                        style: const TextStyle(
                            fontSize: 12, color: Colors.grey),
                      )
                    else
                      Text(
                        recentBlocked.map((e) => e.domain).join(', '),
                        style: const TextStyle(
                            fontSize: 11,
                            color: Colors.red),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, color: Colors.grey),
            ],
          ),
        ),
      ),
    );
  }
}
