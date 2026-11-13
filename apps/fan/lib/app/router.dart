import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/app/shell.dart';
import 'package:jomhor_fan/features/auth/login_screen.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/checkout_screen.dart';
import 'package:jomhor_fan/features/checkout/order_screen.dart';
import 'package:jomhor_fan/features/checkout/zones_screen.dart';
import 'package:jomhor_fan/features/club/club_screen.dart';
import 'package:jomhor_fan/features/clubs/clubs_screen.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/features/events/event_screen.dart';
import 'package:jomhor_fan/features/events/home_screen.dart';
import 'package:jomhor_fan/features/inbox/inbox_screen.dart';
import 'package:jomhor_fan/features/profile/profile_screen.dart';
import 'package:jomhor_fan/features/queue/queue_screen.dart';
import 'package:jomhor_fan/features/tickets/pass_hero.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart';
import 'package:jomhor_fan/features/tickets/tickets_screen.dart';

/// Paths that need a session; events are public (no login prompt to browse), buying is not.
bool needsSession(String path) =>
    path.startsWith('/tickets') ||
    path == '/club' ||
    path.startsWith('/club/') ||
    path.startsWith('/profile') ||
    path.startsWith('/orders') ||
    path == '/inbox' ||
    RegExp('^/events/[^/]+/(zones|checkout|queue)').hasMatch(path);

/// `jomhor-fan://tickets` exactly (the web shop's "Ouvrir dans l'app"); nothing else rides on that host.
bool isTicketsLink(Uri uri) =>
    uri.scheme == appScheme &&
    uri.host == 'tickets' &&
    (uri.path == '' || uri.path == '/') &&
    !uri.hasQuery &&
    !uri.hasFragment;

/// Where to go after login: an in-app path only, never another host.
String afterLogin(String? from) =>
    from != null && from.startsWith('/') && !from.startsWith('//') && !from.startsWith('/login') ? from : '/';

/// Routes follow design/DESIGN.md §Screen inventory. Lot 4 tabs: Accueil · Billets · Club · Profil.
final routerProvider = Provider<GoRouter>((ref) {
  final sessionChanged = ValueNotifier(0);
  ref
    ..listen(sessionProvider, (_, _) => sessionChanged.value++)
    ..onDispose(sessionChanged.dispose);

  final router = GoRouter(
    refreshListenable: sessionChanged,
    redirect: (context, state) {
      // `jomhor-fan://orders/{id}` (card return in Custom Tabs or another browser) → the order screen.
      // `jomhor-fan://tickets` (web shop "Ouvrir dans l'app") → the Billets tab, login-gated below.
      if (state.uri.scheme == appScheme) {
        if (isTicketsLink(state.uri)) return '/tickets';
        final id = returnedOrderId(state.uri.toString());
        return id == null ? '/' : '/orders/$id';
      }
      final path = state.matchedLocation;
      // First launch: the club picker (screen 11), from the app's start only. Deep links (orders, tickets, events)
      // are never intercepted; the picker writes the flag before it navigates.
      if (path == '/' && !ref.read(followsProvider).seen) return '/clubs';
      final signedIn = ref.read(sessionProvider);
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
          StatefulShellBranch(
            routes: [GoRoute(path: '/tickets', builder: (context, state) => const TicketsScreen())],
          ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/club',
                builder: (context, state) =>
                    ClubScreen(organizer: state.uri.queryParameters['organizer'], request: state.extra),
              ),
            ],
          ),
          StatefulShellBranch(
            routes: [GoRoute(path: '/profile', builder: (context, state) => const ProfileScreen())],
          ),
        ],
      ),
      GoRoute(path: '/login', builder: (context, state) => const LoginScreen()),
      GoRoute(path: '/clubs', builder: (context, state) => const ClubsScreen()),
      // Screen 10, full screen, from the bell.
      GoRoute(path: '/inbox', builder: (context, state) => const InboxScreen()),
      GoRoute(
        path: '/events/:id',
        builder: (context, state) => EventScreen(id: int.tryParse(state.pathParameters['id']!) ?? -1),
        routes: [
          // Screen 12 (Lot 5): the waiting room, login-gated like buying.
          GoRoute(
            path: 'queue',
            builder: (context, state) => QueueScreen(eventId: int.tryParse(state.pathParameters['id']!) ?? -1),
          ),
          GoRoute(
            path: 'zones',
            builder: (context, state) => ZonesScreen(eventId: int.tryParse(state.pathParameters['id']!) ?? -1),
          ),
          GoRoute(
            path: 'checkout',
            builder: (context, state) {
              final q = state.uri.queryParameters;
              return CheckoutScreen(
                args: (
                  eventId: int.tryParse(state.pathParameters['id']!) ?? -1,
                  priceCategoryId: q['price'] ?? '',
                  qty: (int.tryParse(q['qty'] ?? '') ?? 1).clamp(1, 20),
                ),
              );
            },
          ),
        ],
      ),
      // Screen 5, full screen (no bottom nav). The id is the ticket's UUID, never a key.
      GoRoute(
        path: '/tickets/:id',
        redirect: (context, state) => isTicketId(state.pathParameters['id']!) ? null : '/tickets',
        builder: (context, state) =>
            TicketScreen(id: state.pathParameters['id']!, origin: PassOrigin.values.asNameMap()[state.extra]),
      ),
      GoRoute(
        path: '/orders/:id',
        redirect: (context, state) => isOrderId(state.pathParameters['id']!) ? null : '/',
        builder: (context, state) => OrderScreen(orderId: state.pathParameters['id']!),
      ),
    ],
  );
  ref.onDispose(router.dispose);
  return router;
});
