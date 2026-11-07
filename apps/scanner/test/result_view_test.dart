import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/features/scan/result_view.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';
import 'package:jomhor_scanner/features/scan/validator.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/app_theme.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

import 'scan_fixtures.dart';

/// The longest result: amber WRONG_ZONE with all 4 facts and 3 gates.
const wrongZone = ScanOutcome(
  verdict: Verdict(
    ScanResult.WRONG_ZONE,
    tokenVersion: versionRotating,
    ticketNo: '7F3A91C2',
    ticketId: 'a1a1a1a1-a1a1-a1a1-a1a1-a1a17f3a91c2',
    zoneId: zoneVirage,
    initials: 'Y. A.',
    correctGates: ['V1', 'V2', 'V3'],
  ),
  scanId: '019a6c3e-0000-7000-8000-000000000001',
  atMs: nowMs,
  elapsedMs: 12,
  zoneName: 'Tribune Virage Sud',
);

double contrast(Color a, Color b) {
  final (x, y) = (a.computeLuminance(), b.computeLuminance());
  return (max(x, y) + 0.05) / (min(x, y) + 0.05);
}

void main() {
  for (final (width, height, scale) in [(360.0, 640.0, 1.0), (412.0, 915.0, 2.0), (360.0, 640.0, 2.0)]) {
    testWidgets(
      '${width.toInt()}x${height.toInt()} @ $scale: no overflow, "Scanner le suivant" on screen and tappable',
      (tester) async {
        tester.view
          ..physicalSize = Size(width, height)
          ..devicePixelRatio = 1;
        tester.platformDispatcher.textScaleFactorTestValue = scale;
        addTearDown(tester.view.reset);
        addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
        var next = 0;
        await tester.pumpWidget(
          MaterialApp(
            theme: buildAppTheme(),
            locale: const Locale('fr'),
            supportedLocales: AppLocalizations.supportedLocales,
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            home: Scaffold(
              body: ResultView(outcome: wrongZone, topLine: 'PORTE N1 · Nord · ENTRÉE', onNext: () => next++),
            ),
          ),
        );
        expect(tester.takeException(), isNull);
        expect(find.text('Portes de cette zone : V1, V2, V3'), findsOneWidget);
        final button = tester.getRect(find.byType(FilledButton));
        expect(
          Offset.zero & Size(width, height),
          predicate<Rect>((r) => r.contains(button.topLeft) && r.contains(button.bottomRight - const Offset(1, 1))),
        );
        await tester.tap(find.text('Scanner le suivant'));
        expect(next, 1);
      },
    );
  }

  test('text >= 4.5:1 on every result colour, on its fact tiles and on the button', () {
    for (final r in ScanResult.values) {
      final bg = resultColor(r);
      final ink = resultInk(r);
      expect(contrast(ink, bg), greaterThanOrEqualTo(4.5), reason: '$r text');
      expect(contrast(ink, factTile(bg)), greaterThanOrEqualTo(4.5), reason: '$r fact tile');
    }
    expect(resultInk(ScanResult.WRONG_ZONE), JomhorColors.ground);
    expect(resultInk(ScanResult.EXPIRED_SLOT), JomhorColors.ground);
    expect(resultInk(ScanResult.DUPLICATE), JomhorColors.text);
  });

  test('DUPLICATE without a gate code drops "porte"', () {
    final l = lookupAppLocalizations(const Locale('fr'));
    ScanOutcome dup(String? gate) => ScanOutcome(
      verdict: Verdict(ScanResult.DUPLICATE, firstAt: nowMs, firstGateCode: gate),
      scanId: 's',
      atMs: nowMs,
      elapsedMs: 1,
    );
    expect(resultReason(l, dup('V2')), contains('porte V2'));
    for (final gate in [null, '']) {
      expect(resultReason(l, dup(gate)), allOf(startsWith('Entré à '), isNot(contains('porte'))));
    }
  });
}
