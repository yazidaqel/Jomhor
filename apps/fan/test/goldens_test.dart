import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/app.dart';
import 'package:jomhor_fan/app/locale.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

import 'cash_order_test.dart' show CashSale, orderId;
import 'goldens.dart';
import 'helpers.dart';
import 'tickets_test.dart' show slotStart, ticketId, ticketJson;

/// FL-17: FR and AR goldens of screens 1, 4, 5 and the cash order (DESIGN.md Lot 4, AR / RTL). Next to the
/// pictures, the rules the pictures show are asserted: right to left, Western digits only, codes and prices in
/// left-to-right isolates. Recorded on Linux with the docker command at the top of goldens.dart.
void main() {
  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    await loadAppFonts();
    LinuxGoldenComparator.install('goldens_test.dart');
  });

  /// Every string on screen (Text and RichText).
  Iterable<String> shownText(WidgetTester tester) =>
      tester.widgetList<RichText>(find.byType(RichText)).map((r) => r.text.toPlainText(includeSemanticsLabels: false));

  /// The rules every AR golden also checks in code.
  void expectArabicRules(WidgetTester tester) {
    expect(Directionality.of(tester.element(find.byType(Scaffold).last)), TextDirection.rtl);
    final text = shownText(tester).join('\n');
    expect(text, isNot(matches(RegExp('[٠-٩۰-۹]'))), reason: 'Western digits only');
  }

  Future<void> phone(WidgetTester tester) async {
    tester.view
      ..physicalSize = const Size(JomhorSize.phoneWidth, JomhorSize.phoneHeight)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
  }

  Future<void> snap(String name) => expectLater(find.byType(JomhorApp), matchesGoldenFile('goldens/$name.png'));

  for (final locale in ['fr', 'ar']) {
    final holder = locale == 'ar' ? 'يزيد ع.' : 'Yazid A.';

    testWidgets('$locale · screen 1 Accueil', (tester) async {
      await phone(tester);
      final clock = TestClock(DateTime.utc(2027, 2, 20, 12));
      Future<http.Response> backend(http.Request r) async => switch ((r.method, r.url.path)) {
        ('GET', '/v1/fan/events') => json([
          eventJson(),
          eventJson(
            id: 2,
            title: 'Atlas FC vs Union Sahel',
            startsAt: '2027-04-03T19:00:00Z',
            metadata: {'opponent': 'Union Sahel', 'competition': 'Championnat', 'matchday': 13},
          ),
        ]),
        ('GET', '/v1/fan/me') => json({'id': 'u1', 'phone': '+212600000001', 'display_name': 'Yazid Aqel'}),
        ('GET', '/v1/fan/tickets') => json({
          'server_time': clock.now.toIso8601String(),
          'tickets': [
            {...ticketJson(), 'holder_label': holder},
          ],
        }),
        ('GET', '/v1/fan/orders') => json({'orders': <Object?>[]}),
        _ => apiError(404, 'NOT_FOUND'),
      };
      await pumpJomhor(
        tester,
        backend: backend,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      if (locale == 'ar') {
        expectArabicRules(tester);
        expect(find.text('الماتش الجاي · فدارنا'), findsOneWidget);
        // The CTA price is an LTR isolate: "50 DH", never "DH 50".
        expect(find.text('شري بلايصك · من \u206650\u00A0DH\u2069'), findsOneWidget);
      }
      await snap('$locale-1-accueil');
    });

    testWidgets('$locale · screen 4 Paiement', (tester) async {
      await phone(tester);
      final clock = TestClock(DateTime.utc(2027, 2, 20, 12));
      final sale = CashSale(clock);
      final c = await pumpJomhor(
        tester,
        backend: sale.handle,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      c.read(routerProvider).go('/events/1/checkout?price=pc-1-0&qty=2');
      await tester.pumpAndSettle();
      if (locale == 'ar') {
        expectArabicRules(tester);
        // The back arrow follows the reading direction (it points right in AR).
        expect(Directionality.of(tester.element(find.byType(BackButtonIcon))), TextDirection.rtl);
        expect(find.text('\u2066100\u00A0DH\u2069'), findsWidgets);
      }
      await snap('$locale-4-paiement');
    });

    testWidgets('$locale · screen 5 Mon billet', (tester) async {
      await phone(tester);
      final clock = TestClock(slotStart.add(const Duration(seconds: 2)));
      Future<http.Response> backend(http.Request r) async => switch ((r.method, r.url.path)) {
        ('GET', '/v1/fan/tickets') => json({
          'server_time': clock.now.toIso8601String(),
          'tickets': [
            {...ticketJson(), 'holder_label': holder},
          ],
        }),
        _ => json({'orders': <Object?>[]}),
      };
      final c = await pumpJomhor(
        tester,
        backend: backend,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      unawaited(c.read(routerProvider).push('/tickets/$ticketId'));
      await tester.pumpAndSettle();
      expect(find.text('EEEE EEEE'), findsOneWidget); // The ticket number, LTR in every locale.
      if (locale == 'ar') {
        expectArabicRules(tester);
        expect(find.text('يزيد ع.'), findsOneWidget); // An Arabic label needs no isolate in AR.
        expect(tester.widget<Text>(find.text('EEEE EEEE')).textDirection, TextDirection.ltr);
      }
      await snap('$locale-5-billet');
    });

    testWidgets('$locale · cash order', (tester) async {
      await phone(tester);
      final clock = TestClock(DateTime.utc(2027, 2, 20, 12));
      final sale = CashSale(clock)
        ..status = 'AWAITING_CASH'
        ..paymentMethod = 'CASH_AGENCY'
        ..paymentStatus = 'PENDING'
        ..reservedUntil = clock.now.add(const Duration(hours: 24));
      final c = await pumpJomhor(
        tester,
        backend: sale.handle,
        tokens: MemoryTokenStore('jwt'),
        clock: clock,
        locale: Locale(locale),
        overrides: [initialLocaleProvider.overrideWithValue(locale)],
      );
      c.read(routerProvider).go('/orders/$orderId');
      await tester.pumpAndSettle();
      expect(tester.widget<Text>(find.byKey(const Key('cash.code'))).textDirection, TextDirection.ltr);
      expect(tester.widget<Text>(find.byKey(const Key('cash.code'))).data, 'JMH 4F7K 29');
      if (locale == 'ar') {
        expectArabicRules(tester);
        expect(find.text('المبلغ بالضبط: \u2066100\u00A0DH\u2069'), findsOneWidget);
      }
      await snap('$locale-cash-order');
    });
  }
}
