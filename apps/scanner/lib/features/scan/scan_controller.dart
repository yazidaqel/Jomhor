import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';
import 'package:jomhor_scanner/features/scan/validator.dart' show uuidString;
import 'package:jomhor_scanner/theme/tokens.g.dart';

bool isAdmitted(ScanResult r) => r == ScanResult.OK || r == ScanResult.OVERRIDE;

final class ScanState {
  const new({this.loaded = false, this.desk, this.outcome, this.failed = false, this.countersStale = false});

  final bool loaded;

  /// Null once loaded = event mode inactive.
  final GateDesk? desk;

  /// The result on screen, if any.
  final ScanOutcome? outcome;

  /// The last scan could not be written (no verdict to show): the gate rescans.
  final bool failed;

  /// The last counters read failed: [desk] keeps its previous values. Never touches [outcome] or [failed], so
  /// a committed verdict stays on screen.
  final bool countersStale;

  /// A red/amber result or a failure holds the screen until dismissed (docs/02 UI rule).
  bool get holding => failed || (outcome != null && !isAdmitted(outcome!.verdict.result));
}

/// Beep + haptic (docs/02 step 8). The tone comes from android `MainActivity` (ToneGenerator, no plugin).
typedef ScanFeedback = Future<void> Function(ScanResult result);

const _tone = MethodChannel('ma.jomhor.scanner/tone');

Future<void> deviceFeedback(ScanResult result) async {
  final ok = isAdmitted(result);
  await (ok ? HapticFeedback.mediumImpact() : HapticFeedback.heavyImpact());
  try {
    await _tone.invokeMethod<void>(ok ? 'ok' : 'refused');
  } on MissingPluginException {
    // No tone channel (tests, non-Android): the screen and haptic still carry the result.
  } on PlatformException {
    // Audio busy or muted: same.
  }
}

final scanFeedbackProvider = Provider<ScanFeedback>((ref) => deviceFeedback);

/// Monotonic milliseconds for the repeat window (a wall-clock or offset change can't reopen it).
final monotonicMsProvider = Provider<int Function()>((ref) {
  final watch = Stopwatch()..start();
  return () => watch.elapsedMilliseconds;
});

/// What "the same presentation" means: the decoded ticket id, so a rotating v2 QR that changes slot while it
/// is still held up is the same ticket; the raw string when it does not decode.
String repeatKey(String raw) {
  try {
    return uuidString(decode(raw).ticketId);
  } on TokenFormatException {
    return raw;
  }
}

/// Screen 6 state: submits camera and keyboard-wedge input to [ScanService], shows the outcome, auto-clears
/// green after `JomhorMotion.greenAutoClearMs`, refreshes the counters.
class ScanController extends Notifier<ScanState> {
  /// The camera keeps reporting a code that stays in view: the same ticket within this window is the same
  /// presentation, not a second scan (nothing is logged; a real second use later gets DUPLICATE).
  static const repeatWindowMs = 3000;

  Timer? _clear;
  String? _lastKey;
  int _lastAt = -repeatWindowMs;
  bool _busy = false;

  /// A wedge read (a deliberate trigger press) that arrived during a scan: run right after it.
  String? _pending;

  @override
  ScanState build() {
    final ticker = Timer.periodic(const Duration(seconds: 30), (_) => unawaited(refresh()));
    ref.onDispose(() {
      ticker.cancel();
      _clear?.cancel();
    });
    unawaited(refresh());
    return const ScanState();
  }

  ScanService get _service => ref.read(scanServiceProvider);

  int _now() => ref.read(monotonicMsProvider)();

  Future<void> refresh() async {
    try {
      final desk = await _service.desk();
      if (ref.mounted) state = ScanState(loaded: true, desk: desk, outcome: state.outcome, failed: state.failed);
    } on Object {
      // Counters only: the verdict on screen (or the idle camera) stays; the screen marks the counters stale.
      if (ref.mounted) {
        state = ScanState(
          loaded: true,
          desk: state.desk,
          outcome: state.outcome,
          failed: state.failed,
          countersStale: true,
        );
      }
    }
  }

  /// [wedge]: from a PDA trigger. Camera reads are dropped while any result shows or a scan runs; a wedge
  /// read during a scan is queued. A red/amber screen ignores both until dismissed (docs/02 UI rule).
  Future<void> submit(String raw, {bool wedge = false}) async {
    final value = raw.trim();
    if (value.isEmpty) return;
    if (_busy) {
      if (wedge) _pending = value;
      return;
    }
    if (state.holding || (!wedge && state.outcome != null)) return;
    final key = repeatKey(value);
    if (key == _lastKey && _now() - _lastAt < repeatWindowMs) return;
    _busy = true;
    _lastKey = key;
    _lastAt = _now();
    _clear?.cancel();
    try {
      final outcome = await _service.scan(value);
      unawaited(ref.read(scanFeedbackProvider)(outcome.verdict.result));
      if (ref.mounted) {
        state = ScanState(loaded: true, desk: state.desk, outcome: outcome, countersStale: state.countersStale);
        if (isAdmitted(outcome.verdict.result)) {
          _clear = Timer(const Duration(milliseconds: JomhorMotion.greenAutoClearMs), dismiss);
        }
      }
    } on EventModeInactive {
      // Locked or event closed meanwhile: refresh() below shows the inactive screen.
    } on Object {
      if (ref.mounted) {
        state = ScanState(loaded: true, desk: state.desk, failed: true, countersStale: state.countersStale);
      }
    } finally {
      _busy = false;
    }
    if (!ref.mounted) return;
    _lastAt = _now();
    await refresh();
    final next = _pending;
    _pending = null;
    if (next != null) await submit(next, wedge: true);
  }

  /// The repeat window restarts here, so a code still held up after a red is dismissed is not rescanned.
  void dismiss() {
    _clear?.cancel();
    if (!ref.mounted) return;
    _lastAt = _now();
    state = ScanState(loaded: state.loaded, desk: state.desk, countersStale: state.countersStale);
  }
}

final NotifierProvider<ScanController, ScanState> scanControllerProvider =
    NotifierProvider.autoDispose<ScanController, ScanState>(ScanController.new);
