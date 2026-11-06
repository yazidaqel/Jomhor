import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The device clock. Tests override it (with [clockProvider]) to move time.
final nowProvider = Provider<DateTime Function()>((ref) => DateTime.now);

/// "Now", re-emitted every 30 s for countdowns and sale states.
final clockProvider = StreamProvider<DateTime>((ref) async* {
  final now = ref.watch(nowProvider);
  yield now();
  yield* Stream.periodic(const Duration(seconds: 30), (_) => now());
});
