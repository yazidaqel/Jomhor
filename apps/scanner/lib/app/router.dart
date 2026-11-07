import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_scanner/features/home/home_screen.dart';
import 'package:jomhor_scanner/features/scan/scan_screen.dart';

/// Scanner routes: `/scan` is DESIGN screen 6. FL-12 adds enrolment, event mode and unlock.
final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(
    routes: [
      GoRoute(path: '/', builder: (context, state) => const HomeScreen()),
      GoRoute(path: '/scan', builder: (context, state) => const ScanScreen()),
    ],
  );
  ref.onDispose(router.dispose);
  return router;
});
