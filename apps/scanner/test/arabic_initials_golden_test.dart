import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/features/scan/result_view.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';
import 'package:jomhor_scanner/features/scan/validator.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/app_theme.dart';

import '../../fan/test/goldens.dart' show LinuxGoldenComparator;
import 'scan_fixtures.dart';

/// FL-17 (docs/04-api.md §Holder names, Arabic labels): a green result whose manifest initials are Arabic,
/// "ي. ع.". A check on one real PDA stays on the dry-run list.
///
/// Coupled to apps/fan on purpose, both read by relative path (as tool/gen_tokens.dart already is):
/// - fonts: the scanner bundles none and the PDA draws with its system fonts, so the fan's files stand in, under
///   the family names the generated styles ask for (Barlow Condensed, Inter, Cairo, IBM Plex Sans Arabic);
/// - comparator: the fan's [LinuxGoldenComparator] (exact on Linux, where the golden is recorded with the docker
///   command at the top of apps/fan/test/goldens.dart), not a copy.
void main() {
  const fonts = '../fan/assets/fonts';

  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    LinuxGoldenComparator.install('arabic_initials_golden_test.dart');
    Future<void> family(String name, List<String> files) async {
      final loader = FontLoader(name);
      for (final f in files) {
        loader.addFont(Future.value(ByteData.sublistView(File('$fonts/$f').readAsBytesSync())));
      }
      await loader.load();
    }

    await family('Barlow Condensed', [
      'BarlowCondensed-SemiBold.ttf',
      'BarlowCondensed-Bold.ttf',
      'BarlowCondensed-ExtraBold.ttf',
    ]);
    await family('Inter', ['Inter-Regular.ttf', 'Inter-Medium.ttf', 'Inter-SemiBold.ttf']);
    // Under the name the generated styles fall back to (the fan's tokens), as a PDA system font would answer it.
    await family('IBM Plex Sans Arabic', [
      'IBMPlexSansArabic-Regular.ttf',
      'IBMPlexSansArabic-SemiBold.ttf',
      'IBMPlexSansArabic-Bold.ttf',
    ]);
    await family('Cairo', ['Cairo-Variable.ttf']);
    // Material icons, from the test bundle's font manifest.
    final manifest = jsonDecode(await rootBundle.loadString('FontManifest.json')) as List<Object?>;
    for (final f in manifest.cast<Map<String, Object?>>().where((f) => f['family'] == 'MaterialIcons')) {
      final loader = FontLoader('MaterialIcons');
      for (final font in (f['fonts']! as List<Object?>).cast<Map<String, Object?>>()) {
        loader.addFont(rootBundle.load(font['asset']! as String));
      }
      await loader.load();
    }
  });

  testWidgets('OK result with the Arabic initials "ي. ع."', (tester) async {
    tester.view
      ..physicalSize = const Size(360, 640)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    const outcome = ScanOutcome(
      verdict: Verdict(
        ScanResult.OK,
        tokenVersion: versionRotating,
        ticketNo: '7F3A91C2',
        ticketId: 'a1a1a1a1-a1a1-a1a1-a1a1-a1a17f3a91c2',
        zoneId: zoneNord,
        initials: 'ي. ع.',
      ),
      scanId: '019a6c3e-0000-7000-8000-000000000002',
      atMs: nowMs,
      elapsedMs: 9,
      zoneName: 'Tribune Nord',
    );
    await tester.pumpWidget(
      MaterialApp(
        debugShowCheckedModeBanner: false,
        theme: buildAppTheme(),
        locale: const Locale('fr'),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        home: Scaffold(
          body: ResultView(outcome: outcome, topLine: 'PORTE N1 · Nord · ENTRÉE', onNext: () {}),
        ),
      ),
    );
    expect(find.text(isolateName('ي. ع.')), findsOneWidget); // In its own isolate: right to left in FR.
    await expectLater(find.byType(ResultView), matchesGoldenFile('goldens/ok-arabic-initials.png'));
  });
}
