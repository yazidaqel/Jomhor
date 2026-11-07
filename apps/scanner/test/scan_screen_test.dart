import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/server_clock.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/scan/result_view.dart';
import 'package:jomhor_scanner/features/scan/scan_controller.dart';
import 'package:jomhor_scanner/features/scan/scan_screen.dart';
import 'package:jomhor_scanner/features/sync/sync_controller.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/app_theme.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

import 'helpers.dart';
import 'scan_fixtures.dart';

final Uint8List valid = ticketBytes(0x11);
final Uint8List virage = ticketBytes(0x55);

/// Camera stand-in that counts how often it is (re)mounted.
int cameraMounts = 0;

class _CameraProbe extends StatefulWidget {
  const new();

  @override
  State<_CameraProbe> createState() => _CameraProbeState();
}

class _CameraProbeState extends State<_CameraProbe> {
  @override
  void initState() {
    super.initState();
    cameraMounts++;
  }

  @override
  Widget build(BuildContext context) => const SizedBox.expand();
}

class _Loaded extends EventKeyHolder {
  @override
  EventKey? build() => EventKey(testKe);
}

/// The sync loop's state without the loop.
class _Sync extends SyncController {
  new({this.online});

  final bool? online;

  @override
  SyncState build() => SyncState(online: online);
}

void main() {
  late ScannerDatabase db;
  late int deviceMs;
  late int mono;
  late List<ScanResult> feedback;
  late ValueChanged<String> camera;

  setUp(() {
    db = memoryDatabase();
    deviceMs = nowMs;
    mono = 0;
    cameraMounts = 0;
    feedback = [];
  });

  Future<void> pumpScan(WidgetTester tester, {bool eventMode = true, bool? online}) async {
    if (eventMode) {
      await tester.runAsync(
        () => seedEventMode(db, {
          ticketId(valid): (zoneNord, 'VALID', 'Y. A.'),
          ticketId(virage): (zoneVirage, 'VALID', null),
        }),
      );
    }
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          databaseProvider.overrideWithValue(db),
          serverClockProvider.overrideWithValue(
            ServerClock(deviceNow: () => DateTime.fromMillisecondsSinceEpoch(deviceMs)),
          ),
          eventKeyProvider.overrideWith(_Loaded.new),
          monotonicMsProvider.overrideWithValue(() => mono),
          scanFeedbackProvider.overrideWithValue((r) async => feedback.add(r)),
          syncControllerProvider.overrideWith(() => _Sync(online: online)),
        ],
        child: MaterialApp(
          theme: buildAppTheme(),
          locale: const Locale('fr'),
          supportedLocales: AppLocalizations.supportedLocales,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          home: ScanScreen(
            camera: (onCode) {
              camera = onCode;
              return const _CameraProbe();
            },
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> scanned(WidgetTester tester, String raw) async {
    camera(raw);
    await tester.pumpAndSettle();
  }

  Future<void> done(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.runAsync(db.close);
  }

  /// The result screen's full-bleed colour.
  Color? background(WidgetTester tester) {
    final scan = [JomhorColors.scanOk, JomhorColors.scanReject, JomhorColors.scanWarn, JomhorColors.scanOverride];
    return tester.widgetList<ColoredBox>(find.byType(ColoredBox)).map((b) => b.color).where(scan.contains).firstOrNull;
  }

  testWidgets('outside event mode the screen says so and shows no camera', (tester) async {
    await pumpScan(tester, eventMode: false);
    expect(find.text('Mode événement inactif'), findsOneWidget);
    await done(tester);
  });

  testWidgets('scanning: top line (gate, mode), unsynced count and counters', (tester) async {
    await pumpScan(tester);
    expect(find.text('PORTE N1 · Nord · ENTRÉE'), findsOneWidget);
    expect(find.text('HORS LIGNE · 0 à synchroniser'), findsOneWidget);
    expect(find.text('ENTRÉS ICI'), findsOneWidget);
    expect(find.text('0 / min'), findsOneWidget);
    await done(tester);
  });

  testWidgets('online: the top line says EN LIGNE with its icon (never colour only)', (tester) async {
    await pumpScan(tester, online: true);
    expect(find.text('EN LIGNE · 0 à synchroniser'), findsOneWidget);
    expect(find.byIcon(Icons.cloud_done_outlined), findsOneWidget);
    await scanned(tester, staticToken(valid));
    expect(
      find.text('EN LIGNE · 1 à synchroniser'),
      findsNWidgets(2),
      reason: 'the result screen and the one under it',
    );
    await done(tester);
  });

  testWidgets('green: word, icon, facts and offline time; auto-clears after 1.5 s; counters move', (tester) async {
    await pumpScan(tester);
    await scanned(tester, staticToken(valid));
    expect(find.text('VALIDE'), findsOneWidget);
    expect(find.byIcon(Icons.check_rounded), findsOneWidget);
    expect(background(tester), JomhorColors.scanOk);
    for (final fact in ['Tribune Nord', isolateName('Y. A.'), '1111 1100', 'PDF · QR fixe']) {
      expect(find.text(fact), findsOneWidget, reason: fact);
    }
    expect(find.textContaining('Validé hors ligne en '), findsOneWidget);
    expect(feedback, [ScanResult.OK]);

    await tester.pump(const Duration(milliseconds: JomhorMotion.greenAutoClearMs));
    await tester.pumpAndSettle();
    expect(find.text('VALIDE'), findsNothing);
    expect(find.text('HORS LIGNE · 1 à synchroniser'), findsOneWidget);
    await done(tester);
  });

  testWidgets('red/amber stays until dismissed; WRONG_ZONE names the correct gates', (tester) async {
    await pumpScan(tester);
    await scanned(tester, staticToken(virage, zone: zoneVirage));
    expect(find.text('MAUVAISE PORTE'), findsOneWidget);
    expect(find.text('Portes de cette zone : V1, V2'), findsOneWidget);
    expect(background(tester), JomhorColors.scanWarn);

    await tester.pump(const Duration(seconds: 10));
    expect(find.text('MAUVAISE PORTE'), findsOneWidget);
    // Input while a red is shown is ignored: the gate deals with this person first.
    await scanned(tester, staticToken(valid));
    expect(find.text('VALIDE'), findsNothing);

    await tester.tap(find.text('Scanner le suivant'));
    await tester.pumpAndSettle();
    expect(find.text('MAUVAISE PORTE'), findsNothing);
    expect(find.text('ENTRÉS ICI'), findsOneWidget);
    await done(tester);
  });

  testWidgets('the same code still in front of the camera is not rescanned; later it is DUPLICATE', (tester) async {
    await pumpScan(tester);
    final token = staticToken(valid);
    await scanned(tester, token);
    await tester.pump(const Duration(milliseconds: JomhorMotion.greenAutoClearMs));
    await tester.pumpAndSettle();
    await scanned(tester, token);
    expect(find.text('DÉJÀ UTILISÉ'), findsNothing);

    mono += ScanController.repeatWindowMs;
    await scanned(tester, token);
    expect(find.text('DÉJÀ UTILISÉ'), findsOneWidget);
    expect(find.textContaining('· porte N1'), findsOneWidget);
    expect(background(tester), JomhorColors.scanReject);
    expect(feedback, [ScanResult.OK, ScanResult.DUPLICATE]);
    await done(tester);
  });

  testWidgets('keyboard wedge (PDA): characters then Enter scan, with no text field or soft keyboard', (tester) async {
    await pumpScan(tester);
    for (final char in staticToken(valid).split('')) {
      await tester.sendKeyEvent(LogicalKeyboardKey.keyA, character: char);
    }
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(find.text('VALIDE'), findsOneWidget);
    expect(find.byType(EditableText), findsNothing);
    await done(tester);
  });

  testWidgets('a scan that cannot be written shows ERREUR, not a verdict', (tester) async {
    await pumpScan(tester);
    await tester.runAsync(
      () => db.customStatement("CREATE TRIGGER fail_scans BEFORE INSERT ON scans BEGIN SELECT RAISE(ABORT, 'x'); END"),
    );
    await scanned(tester, staticToken(valid));
    expect(find.text('ERREUR'), findsOneWidget);
    expect(find.text('VALIDE'), findsNothing);
    await done(tester);
  });

  testWidgets('a rotating QR that changes slot while held is the same ticket: ignored, nothing logged', (tester) async {
    await pumpScan(tester);
    final slot = slotOf(nowMs);
    await scanned(tester, rotatingToken(valid, slot: slot));
    await tester.pump(const Duration(milliseconds: JomhorMotion.greenAutoClearMs));
    await tester.pumpAndSettle();
    mono += 1000;
    await scanned(tester, rotatingToken(valid, slot: slot + 1));
    expect(find.text('DÉJÀ UTILISÉ'), findsNothing);
    expect(await tester.runAsync(() => db.select(db.scans).get()), hasLength(1));

    await scanned(tester, staticToken(virage, zone: zoneVirage));
    expect(find.text('MAUVAISE PORTE'), findsOneWidget);
    expect(await tester.runAsync(() => db.select(db.scans).get()), hasLength(2));
    await done(tester);
  });

  testWidgets('one camera for the screen: not remounted by results', (tester) async {
    await pumpScan(tester);
    await scanned(tester, staticToken(virage, zone: zoneVirage));
    await tester.tap(find.text('Scanner le suivant'));
    await tester.pumpAndSettle();
    await scanned(tester, staticToken(valid));
    await tester.pump(const Duration(milliseconds: JomhorMotion.greenAutoClearMs));
    await tester.pumpAndSettle();
    expect(cameraMounts, 1);
    await done(tester);
  });

  testWidgets('camera reads are ignored while a green shows', (tester) async {
    await pumpScan(tester);
    await scanned(tester, staticToken(valid));
    await scanned(tester, staticToken(virage, zone: zoneVirage));
    expect(find.text('MAUVAISE PORTE'), findsNothing);
    expect(await tester.runAsync(() => db.select(db.scans).get()), hasLength(1));
    await tester.pump(const Duration(milliseconds: JomhorMotion.greenAutoClearMs));
    await tester.pumpAndSettle();
    await done(tester);
  });
}
