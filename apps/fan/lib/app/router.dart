import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/placeholder_screen.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/app/shell.dart';
import 'package:jomhor_fan/features/auth/login_screen.dart';
import 'package:jomhor_fan/features/events/event_screen.dart';
import 'package:jomhor_fan/features/events/home_screen.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';

/// Paths that need a session; events are public (no login prompt to browse).
bool needsSession(String path) => path.startsWith('/tickets') || path.startsWith('/profile');

/// Where to go after login: an in-app path only, never another host.
String afterLogin(String? from) =>
    from != null && from.startsWith('/') && !from.startsWith('//') && !from.startsWith('/login') ? from : '/';

/// Routes follow design/DESIGN.md §Screen inventory. Lot 1 tabs: Accueil · Billets · Profil.
final routerProvider = Provider<GoRouter>((ref) {
  final sessionChanged = ValueNotifier(0);
  ref
    ..listen(sessionProvider, (_, _) => sessionChanged.value++)
    ..onDispose(sessionChanged.dispose);

  final router = GoRouter(
    refreshListenable: sessionChanged,
    redirect: (context, state) {
      final signedIn = ref.read(sessionProvider);
      final path = state.matchedLocation;
      if (path == '/login') return signedIn ? afterLogin(state.uri.queryParameters['from']) : null;
      if (!signedIn && needsSession(path)) {
        return Uri(path: '/login', queryParameters: {'from': state.uri.toString()}).toString();
      }
      return null;
    },
    routes: [
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => AppShell(shell: shell),
        branches: [
          StatefulShellBranch(
            routes: [GoRoute(path: '/', builder: (context, state) => const HomeScreen())],
          ),
          for (final (path, title) in <(String, String Function(AppLocalizations))>[
            ('/tickets', (l) => l.navTickets), // FL-7
            ('/profile', (l) => l.navProfile), // FL-8
          ])
            StatefulShellBranch(
              routes: [
                GoRoute(
                  path: path,
                  builder: (context, state) => PlaceholderScreen(title: title(AppLocalizations.of(context))),
                ),
              ],
            ),
        ],
      ),
      GoRoute(path: '/login', builder: (context, state) => const LoginScreen()),
      GoRoute(
        path: '/events/:id',
        builder: (context, state) => EventScreen(id: int.tryParse(state.pathParameters['id']!) ?? -1),
        routes: [
          // Screen 3 · Choix de zone: FL-6.
          GoRoute(
            path: 'zones',
            builder: (context, state) => PlaceholderScreen(title: AppLocalizations.of(context).chooseZone),
          ),
        ],
      ),
    ],
  );
  ref.onDispose(router.dispose);
  return router;
});
