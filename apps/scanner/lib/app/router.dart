import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_scanner/features/home/home_screen.dart';

/// Scanner routes. FL-11 adds `/scan` (DESIGN screen 6), FL-12 enrolment, event mode and unlock.
final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(
    routes: [GoRoute(path: '/', builder: (context, state) => const HomeScreen())],
  );
  ref.onDispose(router.dispose);
  return router;
});
