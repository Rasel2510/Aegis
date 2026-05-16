import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'providers/ad_block_provider.dart';
import 'screens/home_screen.dart';

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
      home: const HomeScreen(),
    );
  }
}
