import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_scanner/features/home/home_screen.dart';

/// One route: [HomeScreen] picks enrolment, event mode, unlock or the scan screen (DESIGN 6) from the device
/// state, so no screen is reachable out of order (no scan screen while locked, say).
final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(
    routes: [GoRoute(path: '/', builder: (context, state) => const HomeScreen())],
  );
  ref.onDispose(router.dispose);
  return router;
});
