import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'providers/ad_block_provider.dart';
import 'screens/home_screen.dart';
import 'screens/log_screen.dart';
import 'screens/debug_screen.dart';
import 'screens/stats_screen.dart';
import 'screens/dns_settings_screen.dart';
import 'screens/custom_rules_screen.dart';
import 'screens/exclusions_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    ChangeNotifierProvider(
      create: (_) => AdBlockProvider(),
      child: const AdBlockerApp(),
    ),
  );
}

class AdBlockerApp extends StatelessWidget {
  const AdBlockerApp({super.key});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<AdBlockProvider>();
    return MaterialApp(
      title: 'Aegis',
      debugShowCheckedModeBanner: false,
      themeMode: provider.darkMode ? ThemeMode.dark : ThemeMode.light,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF6C63FF)),
        scaffoldBackgroundColor: const Color(0xFFF0F2FF),
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF6C63FF),
          brightness: Brightness.dark,
        ),
        scaffoldBackgroundColor: const Color(0xFF0D0D1A),
      ),
      home: const _RootShell(),
    );
  }
}

/// Root shell with bottom navigation.
/// IndexedStack keeps all screens mounted so streams and state persist
/// across tab switches.
class _RootShell extends StatefulWidget {
  const _RootShell();
  @override
  State<_RootShell> createState() => _RootShellState();
}

class _RootShellState extends State<_RootShell> {
  int _tab = 0;

  static const _screens = [
    HomeScreen(),
    LogScreen(),
    StatsScreen(),
    DebugScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<AdBlockProvider>();
    final blocked  = provider.adsBlocked;

    return Scaffold(
      body: IndexedStack(index: _tab, children: _screens),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        destinations: [
          const NavigationDestination(
            icon: Icon(Icons.shield_outlined),
            selectedIcon: Icon(Icons.shield),
            label: 'Shield',
          ),
          NavigationDestination(
            icon: Badge(
              isLabelVisible: blocked > 0,
              label: Text(
                blocked > 999 ? '999+' : '$blocked',
                style: const TextStyle(fontSize: 9),
              ),
              child: const Icon(Icons.dns_outlined),
            ),
            selectedIcon: const Icon(Icons.dns),
            label: 'Log',
          ),
          const NavigationDestination(
            icon: Icon(Icons.bar_chart_outlined),
            selectedIcon: Icon(Icons.bar_chart),
            label: 'Stats',
          ),
          const NavigationDestination(
            icon: Icon(Icons.monitor_heart_outlined),
            selectedIcon: Icon(Icons.monitor_heart),
            label: 'Debug',
          ),
        ],
      ),
      // Settings accessible from HomeScreen via AppBar action (see below)
    );
  }
}
