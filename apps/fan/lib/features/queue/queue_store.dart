import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:jomhor_fan/app/session.dart';

/// What the phone keeps for one event's room: the queue token, the first position seen (the dots count from it),
/// and the admission token while the turn runs. No personal data; the tokens are credentials, so they live in the
/// Keystore / Keychain with the session token and go on logout.
@immutable
class QueuePass {
  const new({required this.queueToken, this.firstPosition, this.admissionToken, this.admissionUntil});

  factory fromJson(Map<String, Object?> j) => QueuePass(
    queueToken: j['q']! as String,
    firstPosition: j['f'] as int?,
    admissionToken: j['a'] as String?,
    admissionUntil: switch (j['u']) {
      final String t => DateTime.parse(t),
      _ => null,
    },
  );

  final String queueToken;
  final int? firstPosition;
  final String? admissionToken;

  /// When the turn ends on this phone's clock: the server's `expires_at` moved by the server/phone offset.
  final DateTime? admissionUntil;

  /// The turn is running at [now] (this phone's clock).
  bool admitted(DateTime now) => admissionToken != null && (admissionUntil?.isAfter(now) ?? false);

  Map<String, Object?> toJson() => {
    'q': queueToken,
    'f': ?firstPosition,
    'a': ?admissionToken,
    'u': ?admissionUntil?.toUtc().toIso8601String(),
  };

  /// The same pass without its turn (the server refused the admission token).
  QueuePass get withoutAdmission => QueuePass(queueToken: queueToken, firstPosition: firstPosition);

  @override
  bool operator ==(Object other) =>
      other is QueuePass &&
      other.queueToken == queueToken &&
      other.firstPosition == firstPosition &&
      other.admissionToken == admissionToken &&
      other.admissionUntil == admissionUntil;

  @override
  int get hashCode => Object.hash(queueToken, firstPosition, admissionToken, admissionUntil);
}

const _key = 'queue_passes';
const _storage = FlutterSecureStorage(
  iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock_this_device),
);

/// The passes by event id. Survives restarts; wiped on sign-out ([wipeQueuePasses], and here when signed out).
class QueuePasses extends AsyncNotifier<Map<int, QueuePass>> {
  Future<void> _writing = Future.value();

  @override
  Future<Map<int, QueuePass>> build() async {
    if (!ref.watch(sessionProvider)) {
      await wipeQueuePasses();
      return const {};
    }
    try {
      final raw = await _storage.read(key: _key);
      if (raw == null) return const {};
      return {
        for (final MapEntry(:key, :value) in (jsonDecode(raw) as Map<String, Object?>).entries)
          int.parse(key): QueuePass.fromJson(value! as Map<String, Object?>),
      };
    } on Object {
      return const {}; // Unreadable: the room screen joins again (idempotent per account).
    }
  }

  /// Saves [pass] for [eventId]; null forgets it. Never throws.
  ///
  /// One put at a time, read to write: two puts in the same turn both land, and a slow write never lands after a
  /// newer one.
  Future<void> put(int eventId, QueuePass? pass) => _writing = _writing.then((_) => _put(eventId, pass));

  Future<void> _put(int eventId, QueuePass? pass) async {
    try {
      final all = {...await future};
      if (all[eventId] == pass || !ref.mounted || !ref.read(sessionProvider)) return;
      if (pass == null) {
        all.remove(eventId);
      } else {
        all[eventId] = pass;
      }
      state = AsyncData(all);
      final container = ref.container;
      await _storage.write(
        key: _key,
        value: jsonEncode({for (final MapEntry(:key, :value) in all.entries) '$key': value.toJson()}),
      );
      // A sign-out that ran during the write must not leave it behind.
      if (!container.read(sessionProvider)) await wipeQueuePasses();
    } on Object {
      // Not saved: this run keeps it; after a restart the screen joins again.
    }
  }
}

final queuePassesProvider = AsyncNotifierProvider<QueuePasses, Map<int, QueuePass>>(QueuePasses.new);

/// Never throws.
Future<void> wipeQueuePasses() async {
  try {
    await _storage.delete(key: _key);
  } on Object {
    // Keystore unavailable: nothing readable is left either.
  }
}
