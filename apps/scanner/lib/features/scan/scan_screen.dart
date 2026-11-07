import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/features/scan/result_view.dart';
import 'package:jomhor_scanner/features/scan/scan_controller.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';
import 'package:jomhor_scanner/features/supervisor/supervisor_sheet.dart';
import 'package:jomhor_scanner/features/sync/sync_line.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

/// Builds the camera view; it calls `onCode` with each QR string it reads. Tests pass a stand-in.
typedef CameraBuilder = Widget Function(ValueChanged<String> onCode);

/// DESIGN screen 6 (`/scan`): camera (mobile_scanner) and keyboard-wedge input for PDAs (DataWedge / Sunmi
/// scan-to-keystroke type the code then Enter; no vendor SDK, plan lot-2 Q28), counters, full-screen result.
class ScanScreen extends ConsumerStatefulWidget {
  const new({super.key, this.camera});

  final CameraBuilder? camera;

  @override
  ConsumerState<ScanScreen> createState() => _ScanScreenState();
}

class _ScanScreenState extends ConsumerState<ScanScreen> {
  /// Keys further apart than this are a person typing, not a wedge burst: the buffer restarts.
  static const _wedgeGap = Duration(seconds: 1);

  final _wedge = StringBuffer();
  DateTime _lastKey = DateTime.fromMillisecondsSinceEpoch(0);

  /// The supervisor sheet is open: it owns the camera and the keyboard (its ticket field takes wedge input).
  bool _supervising = false;

  void _supervise(bool open) => setState(() => _supervising = open);

  void _fromCamera(String raw) => unawaited(ref.read(scanControllerProvider.notifier).submit(raw));

  void _fromWedge(String raw) => unawaited(ref.read(scanControllerProvider.notifier).submit(raw, wedge: true));

  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    if (_supervising || (event is! KeyDownEvent && event is! KeyRepeatEvent)) return KeyEventResult.ignored;
    final now = DateTime.now();
    if (now.difference(_lastKey) > _wedgeGap) _wedge.clear();
    _lastKey = now;
    final key = event.logicalKey;
    if (key == LogicalKeyboardKey.enter || key == LogicalKeyboardKey.numpadEnter || key == LogicalKeyboardKey.tab) {
      final raw = _wedge.toString();
      _wedge.clear();
      if (raw.isNotEmpty) _fromWedge(raw);
      return KeyEventResult.handled;
    }
    final char = event.character;
    if (char == null || char.isEmpty || char.codeUnitAt(0) < 0x20) return KeyEventResult.ignored;
    _wedge.write(char);
    return KeyEventResult.handled;
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final state = ref.watch(scanControllerProvider);
    // Locked or out of event mode while the sheet is open: it goes, so a later unlock starts on the camera.
    ref.listen(scanControllerProvider, (_, next) {
      if (next.loaded && next.desk == null && _supervising) _supervise(false);
    });
    final desk = state.desk;
    final showing = state.outcome != null || state.failed;
    final topLine = desk == null ? '' : l.scanTopLine(desk.gateCode, desk.gateName, l.scanModeIn);
    final Widget body;
    if (!state.loaded) {
      body = const SizedBox.shrink();
    } else if (_supervising && desk != null) {
      // Android back closes the sheet instead of leaving the app.
      body = PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) _supervise(false);
        },
        child: SupervisorSheet(
          camera: widget.camera ?? _camera,
          ticketNo: state.outcome?.verdict.ticketNo,
          onClose: () => _supervise(false),
        ),
      );
    } else if (desk == null && !showing) {
      body = const _Inactive();
    } else {
      // One camera for the whole screen: it stays mounted (and running) under the result, which is drawn on
      // top; the controller ignores its reads while a result shows.
      body = Stack(
        children: [
          if (desk != null)
            _Scanning(
              desk: desk,
              stale: state.countersStale,
              topLine: topLine,
              camera: (widget.camera ?? _camera)(_fromCamera),
              onSupervisor: () => _supervise(true),
            ),
          if (showing)
            Positioned.fill(
              child: ResultView(
                outcome: state.outcome,
                topLine: topLine,
                unsynced: desk?.unsynced,
                onNext: ref.read(scanControllerProvider.notifier).dismiss,
                onSupervisor: desk == null ? null : () => _supervise(true),
              ),
            ),
        ],
      );
    }
    return Focus(
      autofocus: true,
      onKeyEvent: _onKey,
      child: Scaffold(body: body),
    );
  }
}

Widget _camera(ValueChanged<String> onCode) => QrCamera(onCode: onCode);

class _Scanning extends StatelessWidget {
  const new({
    required this.desk,
    required this.stale,
    required this.topLine,
    required this.camera,
    required this.onSupervisor,
  });

  final GateDesk desk;

  /// The counters could not be refreshed: they show their last values, marked as such.
  final bool stale;
  final String topLine;
  final Widget camera;
  final VoidCallback onSupervisor;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final muted = JomhorType.label.copyWith(color: JomhorColors.textMuted);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(JomhorSpace.xl),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Container(
                  width: JomhorSpace.s,
                  height: JomhorSpace.s,
                  decoration: const BoxDecoration(color: JomhorColors.accent, shape: BoxShape.circle),
                ),
                const SizedBox(width: JomhorSpace.s),
                Expanded(child: Text(topLine, style: JomhorType.label)),
                Flexible(
                  child: SyncLine(unsynced: desk.unsynced, color: JomhorColors.textMuted),
                ),
              ],
            ),
            const SizedBox(height: JomhorSpace.l),
            Expanded(
              child: ClipRRect(
                borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.card)),
                child: ColoredBox(color: JomhorColors.surface, child: camera),
              ),
            ),
            const SizedBox(height: JomhorSpace.m),
            Text(l.scanHint, textAlign: TextAlign.center, style: JomhorType.bodySmall),
            const SizedBox(height: JomhorSpace.l),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(JomhorSpace.l),
                child: Row(
                  children: [
                    _Counter(label: l.scanEntered, value: '${desk.entered}'),
                    _Counter(label: l.scanRefusedCount, value: '${desk.refused}'),
                    _Counter(label: l.scanRate, value: l.scanRateValue(desk.ratePerMin)),
                  ],
                ),
              ),
            ),
            if (stale) ...[
              const SizedBox(height: JomhorSpace.s),
              Text(l.scanCountersStale, textAlign: TextAlign.center, style: muted),
            ],
            const SizedBox(height: JomhorSpace.m),
            // Not focusable: a PDA trigger's Enter must reach the keyboard-wedge handler.
            ExcludeFocus(
              child: OutlinedButton.icon(
                style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(JomhorSize.touchMin)),
                onPressed: onSupervisor,
                icon: const Icon(Icons.lock_outline_rounded),
                label: Text(l.supervisorTitle),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Counter extends StatelessWidget {
  const new({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Expanded(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: JomhorType.caption.copyWith(color: JomhorColors.textMuted)),
        const SizedBox(height: JomhorSpace.xs),
        Text(value, style: JomhorType.number),
      ],
    ),
  );
}

class _Inactive extends StatelessWidget {
  const new();

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(JomhorSpace.xxl),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l.scanInactive, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: JomhorSpace.s),
            Text(l.scanInactiveHint),
          ],
        ),
      ),
    );
  }
}

/// The back camera, QR only. The bundled ML Kit model works without Play Services downloads (offline gates).
/// Also reads the enrol QR (lib/features/enrol).
class QrCamera extends StatefulWidget {
  const new({required this.onCode, super.key});

  final ValueChanged<String> onCode;

  @override
  State<QrCamera> createState() => _QrCameraState();
}

/// Owns the controller for the screen's lifetime; stops the camera when the app leaves the foreground and
/// starts it again on resume (mobile_scanner leaves that to the owner of an explicit controller).
class _QrCameraState extends State<QrCamera> with WidgetsBindingObserver {
  final _controller = MobileScannerController(formats: const [BarcodeFormat.qrCode]);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (!_controller.value.hasCameraPermission) return;
    switch (state) {
      case AppLifecycleState.resumed:
        unawaited(_controller.start());
      case AppLifecycleState.inactive || AppLifecycleState.paused || AppLifecycleState.hidden:
        unawaited(_controller.stop());
      case AppLifecycleState.detached:
        break;
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_controller.dispose());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MobileScanner(
    controller: _controller,
    onDetect: (capture) {
      final raw = capture.barcodes.firstOrNull?.rawValue;
      if (raw != null) widget.onCode(raw);
    },
    errorBuilder: (context, error) => Center(
      child: Padding(
        padding: const EdgeInsets.all(JomhorSpace.xl),
        child: Text(AppLocalizations.of(context).scanCameraError, textAlign: TextAlign.center),
      ),
    ),
  );
}
