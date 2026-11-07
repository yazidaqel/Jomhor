import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

/// docs/06 screen word.
String resultWord(AppLocalizations l, ScanResult r) => switch (r) {
  ScanResult.OK => l.resultOK,
  ScanResult.DUPLICATE => l.resultDUPLICATE,
  ScanResult.BAD_SIG => l.resultBAD_SIG,
  ScanResult.BAD_FORMAT => l.resultBAD_FORMAT,
  ScanResult.UNKNOWN => l.resultUNKNOWN,
  ScanResult.CANCELLED => l.resultCANCELLED,
  ScanResult.BLOCKED => l.resultBLOCKED,
  ScanResult.WRONG_ZONE => l.resultWRONG_ZONE,
  ScanResult.WRONG_EVENT => l.resultWRONG_EVENT,
  ScanResult.EXPIRED_SLOT => l.resultEXPIRED_SLOT,
  ScanResult.OVERRIDE => l.resultOVERRIDE,
};

/// docs/06 colour: green, red, amber (WRONG_ZONE, EXPIRED_SLOT), blue (OVERRIDE).
Color resultColor(ScanResult r) => switch (r) {
  ScanResult.OK => JomhorColors.scanOk,
  ScanResult.OVERRIDE => JomhorColors.scanOverride,
  ScanResult.WRONG_ZONE || ScanResult.EXPIRED_SLOT => JomhorColors.scanWarn,
  _ => JomhorColors.scanReject,
};

/// Text and icon-circle colour on [resultColor]: dark ink on amber, white elsewhere, each >= 4.5:1 on the
/// result colour and on the 14 % white fact tiles (DESIGN Lot 2 adjustments, screen 6; test/result_view_test.dart).
Color resultInk(ScanResult? r) =>
    r == ScanResult.WRONG_ZONE || r == ScanResult.EXPIRED_SLOT ? JomhorColors.ground : JomhorColors.text;

/// Fact tile fill: 14 % white over the result colour.
Color factTile(Color background) => Color.alphaBlend(JomhorColors.text.withValues(alpha: 0.14), background);

/// The icon carries the meaning with the word, never the colour alone (DESIGN §Accessibility).
IconData resultIcon(ScanResult r) => switch (r) {
  ScanResult.OK => Icons.check_rounded,
  ScanResult.OVERRIDE => Icons.verified_user_outlined,
  ScanResult.WRONG_ZONE => Icons.alt_route_rounded,
  ScanResult.EXPIRED_SLOT => Icons.timer_off_outlined,
  ScanResult.DUPLICATE => Icons.content_copy_rounded,
  _ => Icons.close_rounded,
};

String clockTime(int ms) => DateFormat('HH:mm:ss').format(DateTime.fromMillisecondsSinceEpoch(ms));

String? resultReason(AppLocalizations l, ScanOutcome o) {
  final v = o.verdict;
  return switch (v.result) {
    ScanResult.OK => null,
    ScanResult.DUPLICATE => switch ((v.firstAt, v.firstGateCode)) {
      (final at?, final gate?) when gate.isNotEmpty => l.reasonDuplicate(clockTime(at), gate),
      (final at?, _) => l.reasonDuplicateNoGate(clockTime(at)),
      _ => null,
    },
    ScanResult.BAD_SIG => l.reasonBadSig,
    ScanResult.BAD_FORMAT => l.reasonBadFormat,
    ScanResult.UNKNOWN => l.reasonUnknown,
    ScanResult.CANCELLED => l.reasonCancelled,
    ScanResult.BLOCKED => l.reasonBlocked,
    ScanResult.WRONG_ZONE =>
      v.correctGates.isEmpty ? l.reasonWrongZoneNone : l.reasonWrongZone(v.correctGates.join(', ')),
    ScanResult.WRONG_EVENT => l.reasonWrongEvent,
    ScanResult.EXPIRED_SLOT => l.reasonExpiredSlot,
    ScanResult.OVERRIDE => l.reasonOverride,
  };
}

/// DESIGN screen 6 result: full-bleed colour, 120 px circle icon, display 72 word, 2x2 facts at 14 % white,
/// "Validé hors ligne en {ms} ms · {time}", and "Scanner le suivant" pinned at the bottom. White ink, dark on
/// amber. [outcome] null = the scan could not be written.
class ResultView extends StatelessWidget {
  const new({required this.outcome, required this.topLine, required this.onNext, super.key});

  final ScanOutcome? outcome;
  final String topLine;
  final VoidCallback onNext;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final o = outcome;
    final result = o?.verdict.result;
    final color = result == null ? JomhorColors.scanReject : resultColor(result);
    final word = result == null ? l.scanFailed : resultWord(l, result);
    final reason = o == null ? l.scanFailedReason : resultReason(l, o);
    final v = o?.verdict;
    final facts = [
      if (o?.zoneName case final zone?) (l.factZone, zone),
      if (v?.initials case final initials? when initials.isNotEmpty) (l.factHolder, initials),
      if (v?.ticketNo case final no?) (l.factTicketNo, '${no.substring(0, 4)} ${no.substring(4)}'),
      if (v?.tokenVersion case final version?)
        (l.factType, version == versionRotating ? l.tokenRotating : l.tokenStatic),
    ];
    final ink = resultInk(result);
    return ColoredBox(
      color: color,
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(JomhorSpace.xl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // The content scrolls above the pinned button, so a small screen or 200 % text never pushes the
              // only way out of a red screen off-screen.
              Expanded(
                child: LayoutBuilder(
                  builder: (context, box) => SingleChildScrollView(
                    child: ConstrainedBox(
                      constraints: BoxConstraints(minHeight: box.maxHeight),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Text(topLine, style: JomhorType.label.copyWith(color: ink)),
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: JomhorSpace.xl),
                            child: _Body(
                              word: word,
                              icon: result == null ? Icons.error_outline_rounded : resultIcon(result),
                              reason: reason,
                              facts: facts,
                              footer: o == null
                                  ? null
                                  : result == ScanResult.OK
                                  ? l.scanCheckedOk(o.elapsedMs, clockTime(o.atMs))
                                  : l.scanCheckedRefused(o.elapsedMs, clockTime(o.atMs)),
                              color: color,
                              ink: ink,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: JomhorSpace.m),
              // Not focusable: a PDA trigger's Enter must reach the keyboard-wedge handler, not press this.
              ExcludeFocus(
                child: FilledButton(
                  style: FilledButton.styleFrom(backgroundColor: ink, foregroundColor: color),
                  onPressed: onNext,
                  child: Text(l.scanNext, textAlign: TextAlign.center),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Body extends StatelessWidget {
  const new({
    required this.word,
    required this.icon,
    required this.reason,
    required this.facts,
    required this.footer,
    required this.color,
    required this.ink,
  });

  final String word;
  final IconData icon;
  final String? reason;
  final List<(String, String)> facts;
  final String? footer;
  final Color color;
  final Color ink;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Center(
        child: Container(
          width: 120,
          height: 120,
          decoration: BoxDecoration(color: ink, shape: BoxShape.circle),
          child: Icon(icon, size: 64, color: color),
        ),
      ),
      const SizedBox(height: JomhorSpace.xxl),
      FittedBox(
        fit: BoxFit.scaleDown,
        child: Text(word, style: JomhorType.scanResult.copyWith(color: ink)),
      ),
      if (reason case final reason?) ...[
        const SizedBox(height: JomhorSpace.m),
        Text(
          reason,
          textAlign: TextAlign.center,
          style: JomhorType.body.copyWith(fontSize: 18, color: ink),
        ),
      ],
      if (facts.isNotEmpty) ...[
        const SizedBox(height: JomhorSpace.xxl),
        Wrap(
          spacing: JomhorSpace.s,
          runSpacing: JomhorSpace.s,
          children: [
            for (final (label, value) in facts)
              FractionallySizedBox(
                widthFactor: 0.48,
                child: _Fact(label: label, value: value, fill: factTile(color), ink: ink),
              ),
          ],
        ),
      ],
      if (footer case final footer?) ...[
        const SizedBox(height: JomhorSpace.l),
        Text(
          footer,
          textAlign: TextAlign.center,
          style: JomhorType.bodySmall.copyWith(color: ink),
        ),
      ],
    ],
  );
}

class _Fact extends StatelessWidget {
  const new({required this.label, required this.value, required this.fill, required this.ink});

  final String label;
  final String value;
  final Color fill;
  final Color ink;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(JomhorSpace.m),
    decoration: BoxDecoration(color: fill, borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.chip))),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: JomhorType.caption.copyWith(color: ink)),
        const SizedBox(height: JomhorSpace.xs),
        Text(value, style: JomhorType.h2.copyWith(color: ink)),
      ],
    ),
  );
}
