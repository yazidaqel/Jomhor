import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/placeholder_screen.dart';
import 'package:jomhor_fan/app/shell.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';

/// Routes follow design/DESIGN.md §Screen inventory. Lot 1 tabs: Accueil · Billets · Profil.
final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(
    routes: [
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => AppShell(shell: shell),
        branches: [
          for (final (path, title) in <(String, String Function(AppLocalizations))>[
            ('/', (l) => l.navHome),
            ('/tickets', (l) => l.navTickets),
            ('/profile', (l) => l.navProfile),
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
    ],
  );
  ref.onDispose(router.dispose);
  return router;
});
