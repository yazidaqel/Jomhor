import 'dart:async';
import 'dart:convert';
import 'dart:isolate';

import 'package:cryptography/cryptography.dart';
import 'package:drift/drift.dart' show OrderingTerm, Value;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/server_clock.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/event/manifest.dart' show base64UrlBytes;

/// docs/04 §Gate PINs: `pbkdf2-sha256$10000$<salt>$<hash>`, PBKDF2-HMAC-SHA256 of the UTF-8 PIN, 16-byte salt,
/// 32-byte hash, base64url without padding. False for any other verifier (other rounds or sizes included).
const pinIterations = 10000;

Future<bool> verifyPin(String pin, String verifier) async {
  final parts = verifier.split(r'$');
  if (parts.length != 4 || parts[0] != 'pbkdf2-sha256' || parts[1] != '$pinIterations') return false;
  final List<int> salt;
  final List<int> hash;
  try {
    salt = base64UrlBytes(parts[2]);
    hash = base64UrlBytes(parts[3]);
  } on FormatException {
    return false;
  }
  if (salt.length != 16 || hash.length != 32) return false;
  final derived = await Pbkdf2.hmacSha256(
    iterations: pinIterations,
    bits: 256,
  ).deriveKey(secretKey: SecretKey(utf8.encode(pin)), nonce: salt);
  final bytes = await derived.extractBytes();
  var diff = 0;
  for (var i = 0; i < hash.length; i++) {
    diff |= bytes[i] ^ hash[i];
  }
  return diff == 0;
}

/// Proof that a SUPERVISOR typed their PIN on this device: only [StaffSession.verifySupervisor] makes one, and
/// every supervisor action (override, gate change, lookup) takes one. It is good for [ttl] after the PIN, so a
/// device handed back to the gate agent cannot override under the supervisor's id.
final class SupervisorGrant {
  const new _(this.staffId, this.grantedAtMs);

  static const ttl = Duration(seconds: 60);

  /// The supervisor's `staff.staff_id` (the scan row's `supervisor_id`).
  final String staffId;

  /// Server-corrected epoch ms of the PIN check.
  final int grantedAtMs;

  /// Within [ttl] of the PIN at [nowMs]; a clock that went backwards past the PIN time also ends it.
  bool validAt(int nowMs) => nowMs >= grantedAtMs && nowMs - grantedAtMs <= ttl.inMilliseconds;
}

/// The answer to an unlock attempt. `lockedUntilMs` set = locked out (server-corrected epoch ms).
typedef UnlockOutcome = ({bool ok, int attemptsLeft, int? lockedUntilMs});

/// The staff lock of plan lot-2 Q31: unlock = a name from `staff` + its PIN, checked on the device (no network).
/// 5 wrong PINs lock the device for 5 min (kept in secure storage, so an app restart does not reset it); 10 min
/// without a touch or a scan locks it. The PIN is a UI lock; the device key and revocation are the boundary.
class StaffSession {
  new(this._db, this._storage, this._clock);

  static const maxFailures = 5;
  static const lockout = Duration(minutes: 5);
  static const idleAfter = Duration(minutes: 10);

  /// `failures:lockedUntilMs` (lockedUntilMs empty when not locked).
  static const lockKey = 'pin_lock';

  final ScannerDatabase _db;
  final FlutterSecureStorage _storage;
  final ServerClock _clock;
  Timer? _idle;

  Future<UnlockOutcome> unlock(String staffId, String pin) async {
    final outcome = await _check(staffId, pin);
    if (outcome.ok) {
      await _db.update(_db.config).write(ConfigCompanion(staffId: Value(staffId)));
      _restartIdle();
    }
    return outcome;
  }

  /// The supervisor sheet (docs/02 §Supervisor override): a SUPERVISOR's own PIN, checked like [unlock] and on
  /// the same 5-try lockout, without touching the unlocked staff (`config.staff_id` stays the gate agent). A
  /// GATE member, even with the right PIN, gets no grant.
  Future<(UnlockOutcome, SupervisorGrant?)> verifySupervisor(String staffId, String pin) async {
    final outcome = await _check(staffId, pin, role: 'SUPERVISOR');
    return (outcome, outcome.ok ? SupervisorGrant._(staffId, _clock.nowMs()) : null);
  }

  /// [pin] against [staffId]'s verifier (of [role], when given), counting failures towards the lockout.
  Future<UnlockOutcome> _check(String staffId, String pin, {String? role}) async {
    final now = _clock.nowMs();
    final stored = ((await _storage.read(key: lockKey)) ?? '').split(':');
    var failures = int.tryParse(stored.first) ?? 0;
    final until = stored.length > 1 ? int.tryParse(stored[1]) : null;
    if (until != null && now < until) return (ok: false, attemptsLeft: 0, lockedUntilMs: until);
    if (until != null) failures = 0;

    final row = await (_db.select(_db.staff)..where((s) => s.staffId.equals(staffId))).getSingleOrNull();
    final verifier = role == null || row?.role == role ? row?.pinVerifier : null;
    // PBKDF2 (10,000 rounds) off the UI isolate so the PIN pad does not freeze.
    if (verifier != null && await Isolate.run(() => verifyPin(pin, verifier))) {
      await _storage.delete(key: lockKey);
      return (ok: true, attemptsLeft: maxFailures, lockedUntilMs: null);
    }
    failures++;
    if (failures >= maxFailures) {
      final lockedUntil = now + lockout.inMilliseconds;
      await _storage.write(key: lockKey, value: '0:$lockedUntil');
      return (ok: false, attemptsLeft: 0, lockedUntilMs: lockedUntil);
    }
    await _storage.write(key: lockKey, value: '$failures:');
    return (ok: false, attemptsLeft: maxFailures - failures, lockedUntilMs: null);
  }

  /// Back to the unlock screen (idle, app start).
  Future<void> lock() async {
    _idle?.cancel();
    _idle = null;
    await _db.update(_db.config).write(const ConfigCompanion(staffId: Value(null)));
  }

  /// A touch or a scan while unlocked restarts the idle countdown.
  void touch() {
    if (_idle != null) _restartIdle();
  }

  void _restartIdle() {
    _idle?.cancel();
    _idle = Timer(idleAfter, () => unawaited(lock()));
  }

  void dispose() => _idle?.cancel();
}

final staffSessionProvider = Provider<StaffSession>((ref) {
  final session = StaffSession(
    ref.watch(databaseProvider),
    ref.watch(secureStorageProvider),
    ref.watch(serverClockProvider),
  );
  ref.onDispose(session.dispose);
  return session;
});

/// The unlock list: GATE and SUPERVISOR staff from `GET /gate/events`, by label.
final staffListProvider = StreamProvider<List<StaffData>>((ref) {
  final db = ref.watch(databaseProvider);
  return (db.select(db.staff)..orderBy([(s) => OrderingTerm.asc(s.label)])).watch();
});
