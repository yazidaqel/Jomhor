import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/locale.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/features/auth/login_screen.dart';
import 'package:jomhor_fan/features/profile/profile_controller.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'helpers.dart';

Map<String, Object?> meJson([String? name = 'Yazid Aqel', String locale = 'fr']) => {
  'id': 'u1',
  'phone': '+212612345678',
  'display_name': name,
  'locale': locale,
};

/// Same shape as the app writes it (FL-7), so the launch read keeps it.
void seedTicketCache() => FlutterSecureStorage.setMockInitialValues({
  'fan_tickets_cache': jsonEncode({
    'offset_ms': 0,
    'response': {'server_time': '2027-02-20T12:00:00Z', 'tickets': <Object?>[]},
  }),
});

Future<String?> savedCache() => const FlutterSecureStorage().read(key: 'fan_tickets_cache');

void main() {
  final requests = <http.Request>[];
  setUp(requests.clear);

  /// `/fan/me` answers [me]; logout answers [logout], or throws (offline) when null; PATCH answers [patch].
  Future<http.Response> Function(http.Request) backend({
    http.Response? logout,
    http.Response? patch,
    bool offline = false,
    String serverLocale = 'fr',
  }) => (r) async {
    requests.add(r);
    if (offline) throw Exception('no network');
    return switch ((r.method, r.url.path)) {
      ('GET', '/v1/fan/me') => json(meJson('Yazid Aqel', serverLocale)),
      ('PATCH', '/v1/fan/me') => patch ?? json(meJson()),
      ('POST', '/v1/fan/me/logout') => logout ?? http.Response('', 204),
      ('GET', '/v1/fan/tickets') => json({'server_time': '2027-02-20T12:00:00Z', 'tickets': <Object?>[]}),
      _ => json(<Object?>[]),
    };
  };

  List<http.Request> calls(String method, String path) =>
      requests.where((r) => r.method == method && r.url.path == '/v1$path').toList();

  Future<ProviderContainer> openProfile(
    WidgetTester tester,
    Future<http.Response> Function(http.Request) b,
    MemoryTokenStore tokens,
  ) async {
    seedTicketCache();
    final c = await pumpJomhor(tester, backend: b, tokens: tokens, keepSecureStorage: true);
    c.read(routerProvider).go('/profile');
    await tester.pumpAndSettle();
    return c;
  }

  Future<void> confirm(WidgetTester tester, String button) async {
    await tester.ensureVisible(find.byKey(Key(button)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(Key(button)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('profile.logoutConfirm')));
    await tester.pumpAndSettle();
  }

  testWidgets('shows name, read-only phone and the three languages, FR picked', (tester) async {
    await openProfile(tester, backend(), MemoryTokenStore('jwt'));
    expect(find.text('Yazid Aqel'), findsOneWidget);
    expect(find.text('+212612345678'), findsOneWidget);
    expect(find.descendant(of: find.byKey(const Key('profile.phone')), matching: find.byType(TextField)), findsNothing);
    expect(tester.widget<ListTile>(find.byKey(const Key('profile.lang.fr'))).selected, isTrue);
    expect(tester.widget<ListTile>(find.widgetWithText(ListTile, 'العربية')).selected, isFalse);
    expect(tester.widget<ListTile>(find.widgetWithText(ListTile, 'English')).selected, isFalse);
  });

  testWidgets('the language switch applies at once, persists on the phone and PATCHes /fan/me', (tester) async {
    SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
    await openProfile(tester, backend(), MemoryTokenStore('jwt'));
    expect(Directionality.of(tester.element(find.byKey(const Key('profile.lang.ar')))), TextDirection.ltr);

    await tester.ensureVisible(find.byKey(const Key('profile.lang.ar')));
    await tester.tap(find.byKey(const Key('profile.lang.ar')));
    await tester.pumpAndSettle();

    // Arabic, right to left, in Darija; the pick is saved and sent to the server.
    expect(Directionality.of(tester.element(find.byKey(const Key('profile.lang.ar')))), TextDirection.rtl);
    expect(find.text('حسابي'), findsWidgets);
    expect(tester.widget<ListTile>(find.byKey(const Key('profile.lang.ar'))).selected, isTrue);
    expect(await savedLocale(SharedPreferencesAsync()), 'ar');
    expect(jsonDecode(calls('PATCH', '/fan/me').single.body), {'locale': 'ar'});

    await tester.tap(find.byKey(const Key('profile.lang.en')));
    await tester.pumpAndSettle();
    expect(find.text('Profile'), findsWidgets);
    expect(await savedLocale(SharedPreferencesAsync()), 'en');
    expect(jsonDecode(calls('PATCH', '/fan/me').last.body), {'locale': 'en'});
  });

  testWidgets('the saved language wins at startup; /fan/me with another one gets one PATCH', (tester) async {
    seedTicketCache();
    final c = await pumpJomhor(
      tester,
      backend: backend(),
      tokens: MemoryTokenStore('jwt'),
      keepSecureStorage: true,
      overrides: [initialLocaleProvider.overrideWithValue('ar')],
    );
    c.read(routerProvider).go('/profile');
    await tester.pumpAndSettle();
    expect(Directionality.of(tester.element(find.byKey(const Key('profile.lang.ar')))), TextDirection.rtl);
    // The server said fr: told once, and a second /fan/me read sends nothing more.
    expect(
      [for (final r in calls('PATCH', '/fan/me')) jsonDecode(r.body)],
      [
        {'locale': 'ar'},
      ],
    );
    c.invalidate(profileProvider);
    await tester.pumpAndSettle();
    expect(calls('PATCH', '/fan/me'), hasLength(1));
  });

  testWidgets('no saved choice: the device language wins over the server default and is PATCHed once', (tester) async {
    seedTicketCache();
    final c = await pumpJomhor(
      tester,
      backend: backend(),
      tokens: MemoryTokenStore('jwt'),
      keepSecureStorage: true,
      locale: const Locale('ar', 'MA'),
    );
    c.read(routerProvider).go('/profile');
    await tester.pumpAndSettle();
    // The server said fr (its default): the Arabic phone stays Arabic, and the server learns it.
    expect(Directionality.of(tester.element(find.byKey(const Key('profile.lang.ar')))), TextDirection.rtl);
    expect(
      [for (final r in calls('PATCH', '/fan/me')) jsonDecode(r.body)],
      [
        {'locale': 'ar'},
      ],
    );
    c.invalidate(profileProvider);
    await tester.pumpAndSettle();
    expect(calls('PATCH', '/fan/me'), hasLength(1));
  });

  testWidgets('the server language never changes the app; the same language sends no PATCH', (tester) async {
    seedTicketCache();
    final c = await pumpJomhor(
      tester,
      backend: backend(serverLocale: 'ar'),
      tokens: MemoryTokenStore('jwt'),
      keepSecureStorage: true,
      overrides: [initialLocaleProvider.overrideWithValue('ar')],
    );
    c.read(routerProvider).go('/profile');
    await tester.pumpAndSettle();
    expect(calls('PATCH', '/fan/me'), isEmpty);
    expect(Directionality.of(tester.element(find.byKey(const Key('profile.lang.ar')))), TextDirection.rtl);
  });

  testWidgets('a French phone with a server in ar stays French and tells the server', (tester) async {
    seedTicketCache();
    final c = await pumpJomhor(
      tester,
      backend: backend(serverLocale: 'ar'),
      tokens: MemoryTokenStore('jwt'),
      keepSecureStorage: true,
    );
    c.read(routerProvider).go('/profile');
    await tester.pumpAndSettle();
    expect(Directionality.of(tester.element(find.byKey(const Key('profile.lang.fr')))), TextDirection.ltr);
    expect(
      [for (final r in calls('PATCH', '/fan/me')) jsonDecode(r.body)],
      [
        {'locale': 'fr'},
      ],
    );
  });

  testWidgets('logout calls the API and wipes the token and the ticket cache even when offline', (tester) async {
    final tokens = MemoryTokenStore('jwt');
    final c = await openProfile(tester, backend(offline: true), tokens);
    expect(await savedCache(), isNotNull);
    // Offline: /fan/me fails, logout is still there.
    await confirm(tester, 'profile.logout');

    final logout = calls('POST', '/fan/me/logout').single;
    expect(logout.headers['Authorization'], 'Bearer jwt');
    expect(tokens.token, isNull);
    expect(await savedCache(), isNull);
    expect(find.byType(LoginScreen), findsOneWidget);
    expect(c.read(routerProvider).state.uri.path, '/login');
  });

  testWidgets('logout waits at most 5 s for a server that never answers', (tester) async {
    final tokens = MemoryTokenStore('jwt');
    final never = Completer<http.Response>();
    await openProfile(tester, (r) => r.url.path == '/v1/fan/me/logout' ? never.future : backend()(r), tokens);
    await tester.ensureVisible(find.byKey(const Key('profile.logout')));
    await tester.tap(find.byKey(const Key('profile.logout')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('profile.logoutConfirm')));
    await tester.pump(const Duration(seconds: 4));
    expect(tokens.token, 'jwt');
    await tester.pump(const Duration(seconds: 2));
    expect((tokens.token, await savedCache()), (null, null));
    // Let the client's own 20 s timeout fire so no timer is left pending.
    await tester.pump(const Duration(seconds: 20));
    await tester.pumpAndSettle();
    expect(find.byType(LoginScreen), findsOneWidget);
  });

  testWidgets('logout on a server error still wipes local state', (tester) async {
    final tokens = MemoryTokenStore('jwt');
    await openProfile(tester, backend(logout: apiError(500, 'INTERNAL')), tokens);
    await confirm(tester, 'profile.logout');
    expect(jsonDecode(calls('POST', '/fan/me/logout').single.body), {'all': false});
    expect((tokens.token, await savedCache()), (null, null));
  });

  testWidgets('"tous mes appareils" sends all: true', (tester) async {
    final tokens = MemoryTokenStore('jwt');
    await openProfile(tester, backend(), tokens);
    await confirm(tester, 'profile.logoutAll');
    expect(jsonDecode(calls('POST', '/fan/me/logout').single.body), {'all': true});
    expect((tokens.token, await savedCache()), (null, null));
    expect(find.byType(SnackBar), findsNothing);
  });

  testWidgets('"tous mes appareils" offline: signed out here, and told the other devices are not', (tester) async {
    final tokens = MemoryTokenStore('jwt');
    await openProfile(tester, backend(offline: true), tokens);
    await confirm(tester, 'profile.logoutAll');
    expect((tokens.token, await savedCache()), (null, null));
    expect(find.textContaining("tes autres appareils n'ont pas pu l'être"), findsOneWidget);
  });

  testWidgets('cancelling the dialog keeps the session and makes no call', (tester) async {
    final tokens = MemoryTokenStore('jwt');
    await openProfile(tester, backend(), tokens);
    await tester.ensureVisible(find.byKey(const Key('profile.logout')));
    await tester.tap(find.byKey(const Key('profile.logout')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();
    expect(calls('POST', '/fan/me/logout'), isEmpty);
    expect(tokens.token, 'jwt');
  });

  testWidgets('name edit: PATCH /fan/me and the name as the server stored it', (tester) async {
    await openProfile(tester, backend(patch: json(meJson('Yazid Aqel-Benani'))), MemoryTokenStore('jwt'));
    await tester.tap(find.byKey(const Key('profile.editName')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('profile.nameField')), '  Yazid   Aqel-Benani ');
    await tester.tap(find.byKey(const Key('profile.saveName')));
    await tester.pumpAndSettle();

    expect(jsonDecode(calls('PATCH', '/fan/me').single.body), {'display_name': '  Yazid   Aqel-Benani '});
    expect(find.byKey(const Key('profile.nameField')), findsNothing);
    expect(find.text('Yazid Aqel-Benani'), findsOneWidget);
  });

  testWidgets('name edit: a 422 on display_name shows the field error and keeps the field', (tester) async {
    final refused = json({
      'code': 'VALIDATION_FAILED',
      'message': 'bad name',
      'details': {
        'fields': {'display_name': 'must be Latin letters'},
      },
    }, 422);
    await openProfile(tester, backend(patch: refused), MemoryTokenStore('jwt'));
    await tester.tap(find.byKey(const Key('profile.editName')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('profile.nameField')), 'يزيد');
    await tester.tap(find.byKey(const Key('profile.saveName')));
    await tester.pumpAndSettle();

    expect(
      find.text('Prénom et nom en lettres latines ou arabes, sans mélanger les deux, de 2 à 60 caractères.'),
      findsOneWidget,
    );
    expect(find.byKey(const Key('profile.nameField')), findsOneWidget);
    expect(find.text('bad name'), findsNothing, reason: 'never the server message');
  });
}
