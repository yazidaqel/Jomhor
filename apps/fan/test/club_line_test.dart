import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'club_test.dart' show ClubBackend;
import 'goldens.dart';
import 'helpers.dart';

/// Accueil's club lines ("Follow" / "Following" at the end, in the ~126 px the calendar row leaves them) and screen 7
/// above them, with the app's real fonts: a file of its own, since the fonts stay loaded for the whole file.
void main() {
  setUpAll(loadAppFonts);
  setUp(() => SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty());

  Future<ProviderContainer> pumpHome(WidgetTester tester, String locale) {
    tester.view
      ..physicalSize = const Size(JomhorSize.phoneWidth, 1600)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    return pumpJomhor(
      tester,
      backend: ClubBackend().handle,
      tokens: MemoryTokenStore('jwt'),
      follows: (ids: const [testOrganizer], seen: true, chip: chipAll),
      locale: Locale(locale),
    );
  }

  bool keyed(Widget w, String prefix) =>
      w.key is ValueKey<String> && (w.key! as ValueKey<String>).value.startsWith(prefix);

  testWidgets('150 % text: Accueil and screen 7 non-member lay out in en, fr and ar', (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    for (final locale in ['en', 'fr', 'ar']) {
      final c = await pumpHome(tester, locale);
      expect(find.byWidgetPredicate((w) => keyed(w, 'home.following.')), findsWidgets);
      expect(find.byWidgetPredicate((w) => keyed(w, 'home.follow.')), findsWidgets);
      expect(tester.takeException(), isNull, reason: '$locale Accueil');
      c.read(routerProvider).go('/club');
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull, reason: '$locale screen 7');
      await tester.pumpWidget(const SizedBox());
    }
  });

  testWidgets('100 % text: EN "Following" shows whole', (tester) async {
    await pumpHome(tester, 'en');
    final following = find.byWidgetPredicate((w) => keyed(w, 'home.following.'));
    expect(following, findsWidgets);
    final labels = find.descendant(
      of: find.descendant(of: following, matching: find.text('Following')),
      matching: find.byType(RichText),
    );
    expect(labels, findsWidgets);
    for (final p in tester.renderObjectList<RenderParagraph>(labels)) {
      expect(p.didExceedMaxLines, isFalse);
    }
    expect(tester.takeException(), isNull);
  });
}
