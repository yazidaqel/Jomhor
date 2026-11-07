import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/app/app.dart';
import 'package:jomhor_scanner/app/config.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';

Future<void> main() async {
  AppConfig.checkApiUrl();
  WidgetsFlutterBinding.ensureInitialized();
  final db = ScannerDatabase.open();
  final config = await db.openLocked();
  runApp(
    ProviderScope(
      overrides: [
        databaseProvider.overrideWithValue(db),
        serverClockProvider.overrideWithValue(
          ServerClock(offsetMs: config.serverTimeOffsetMs, measured: config.lastServerTimeAt != null),
        ),
      ],
      child: const ScannerApp(),
    ),
  );
}
