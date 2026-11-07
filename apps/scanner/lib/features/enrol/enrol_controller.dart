import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/features/enrol/enrol_service.dart';
import 'package:jomhor_scanner/features/event/event_service.dart';

final class EnrolState {
  const new({this.scanning = false, this.code, this.busy = false, this.error, this.fingerprint});

  /// The camera is open on the enrol QR.
  final bool scanning;

  /// The enrol code read from the QR.
  final String? code;
  final bool busy;

  /// [EnrolController.codeInvalid], [EnrolController.labelInvalid] or a [GateApiError] code.
  final String? error;

  /// This device's key fingerprint (`AB12 CD34`), once a key exists.
  final String? fingerprint;
}

/// Enrôlement screen: scan the enrol QR, type a label, enrol; then the fingerprint while PENDING.
class EnrolController extends Notifier<EnrolState> {
  static const codeInvalid = 'CODE_INVALID';
  static const labelInvalid = 'LABEL_INVALID';

  @override
  EnrolState build() {
    unawaited(_loadFingerprint());
    return const EnrolState();
  }

  EnrolService get _service => ref.read(enrolServiceProvider);

  Future<void> _loadFingerprint() async {
    String? fp;
    try {
      fp = await _service.currentFingerprint();
    } on Object {
      // Keystore unreadable: no fingerprint on screen; enrolling reports the error.
    }
    if (ref.mounted) {
      state = EnrolState(scanning: state.scanning, code: state.code, error: state.error, fingerprint: fp);
    }
  }

  void openCamera() => state = EnrolState(scanning: true, fingerprint: state.fingerprint);

  void onCode(String raw) {
    if (!state.scanning) return;
    final code = raw.trim();
    state = enrolCodePattern.hasMatch(code)
        ? EnrolState(code: code, fingerprint: state.fingerprint)
        : EnrolState(scanning: true, error: codeInvalid, fingerprint: state.fingerprint);
  }

  Future<void> submit(String label) async {
    final code = state.code;
    if (code == null || state.busy) return;
    if (!validLabel(label)) {
      state = EnrolState(code: code, error: labelInvalid, fingerprint: state.fingerprint);
      return;
    }
    state = EnrolState(code: code, busy: true, fingerprint: state.fingerprint);
    String? error;
    try {
      await _service.enrol(code, label);
    } on GateApiError catch (e) {
      error = e.code;
    } on Object {
      error = GateApiError.unexpectedResponse;
    } finally {
      // Never left busy. UNAUTHORIZED = bad or expired enrol code: scan a fresh QR.
      if (ref.mounted) {
        state = EnrolState(code: error == 'UNAUTHORIZED' ? null : code, error: error, fingerprint: state.fingerprint);
      }
    }
    if (error == null) await _loadFingerprint();
  }
}

final NotifierProvider<EnrolController, EnrolState> enrolControllerProvider =
    NotifierProvider.autoDispose<EnrolController, EnrolState>(EnrolController.new);

/// While PENDING: `GET /gate/events` every 10 s; the first success marks the device ACTIVE (docs/04: PENDING
/// answers 403 `DEVICE_NOT_ACTIVE`, which the app polls).
final Provider<void> pendingPollProvider = Provider.autoDispose<void>((ref) {
  Future<void> poll() async {
    try {
      await ref.read(eventServiceProvider).refresh();
    } on Object {
      // Still PENDING, or offline: the next tick tries again.
    }
  }

  final timer = Timer.periodic(const Duration(seconds: 10), (_) => unawaited(poll()));
  ref.onDispose(timer.cancel);
  unawaited(poll());
});
