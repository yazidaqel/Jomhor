// Screen 5 gate side and gate row (DESIGN.md §Lot 4 adjustments, "Screen 5 front" and "Screen 5 gate side"). The
// gate side works offline from the saved ticket; only the load chips need the network (lot-4 Q88).

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/features/tickets/gate_load.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:url_launcher/url_launcher.dart';

/// "Ouvrir dans Plans" for [g] on [platform]: `geo:` on Android, Apple Maps on iOS; null without coordinates.
Uri? mapsUrl(TicketGate g, TargetPlatform platform) {
  final (lat, lng) = (g.latitude, g.longitude);
  if (lat == null || lng == null) return null;
  final ll = '${lat.toStringAsFixed(6)},${lng.toStringAsFixed(6)}';
  // Parentheses would end the `geo:` label early; Uri.parse does not keep them escaped.
  final label = Uri.encodeComponent((g.name ?? g.code).replaceAll(RegExp('[()]'), ''));
  return Uri.parse(switch (platform) {
    TargetPlatform.iOS || TargetPlatform.macOS => 'https://maps.apple.com/?ll=$ll&q=$label',
    _ => 'geo:$ll?q=$ll($label)',
  });
}

/// Opens a Maps link in the phone's maps app. Tests override it.
final openMapsProvider = Provider<Future<void> Function(Uri url)>(
  (ref) => (url) async {
    try {
      await launchUrl(url, mode: LaunchMode.externalApplication);
    } on Object {
      // No maps app: the directions stay on screen.
    }
  },
);

/// "Porte 3 plus fluide en ce moment": a LOW gate of the zone while your gate (the first, lot-4 Q81) is HIGH.
TicketGate? smootherGate(List<TicketGate> gates, GateLoad? load, DateTime serverNow) {
  if (load == null || gates.isEmpty || load.levelOf(gates.first.code, serverNow) != GateLevel.high) return null;
  return gates.skip(1).where((g) => load.levelOf(g.code, serverNow) == GateLevel.low).firstOrNull;
}

/// "Porte 4" (the code kept left to right in AR).
String gateName(AppLocalizations l, String code) => l.ticketGatesList(1, l.ltr(code));

/// "Porte 6 · Porte Est" in the locale's order (`dotPair`), the name muted.
TextSpan _otherGate(AppLocalizations l, TicketGate g) {
  final code = gateName(l, g.code);
  final name = g.name;
  if (name == null) return TextSpan(text: code);
  final full = l.dotPair(code, l.isolate(name));
  final at = full.indexOf(code);
  const muted = TextStyle(fontWeight: FontWeight.w400, color: JomhorColors.ticketMuted);
  return TextSpan(
    children: [
      if (at > 0) TextSpan(text: full.substring(0, at), style: muted),
      TextSpan(text: code),
      TextSpan(text: full.substring(at + code.length), style: muted),
    ],
  );
}

/// A gate's load: Fluide · Modérée · Chargée; nothing when unknown, stale or offline.
class LoadChip extends StatelessWidget {
  const new(this.level, {this.onPaper = false, super.key});

  final GateLevel level;

  /// On the ticket paper rather than the dark ground.
  final bool onPaper;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final (text, dot) = switch (level) {
      GateLevel.low => (l.gateLevelLow, onPaper ? JomhorColors.success : JomhorColors.successText),
      GateLevel.medium => (l.gateLevelMedium, onPaper ? JomhorColors.scanWarn : JomhorColors.accent),
      GateLevel.high => (l.gateLevelHigh, onPaper ? JomhorColors.scanReject : JomhorColors.danger),
      GateLevel.unknown => (null, null),
    };
    if (text == null || dot == null) return const SizedBox.shrink();
    return Container(
      key: Key('gate.load.${level.name}'),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: dot.withValues(alpha: onPaper ? 0.1 : 0.14),
        borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.pill)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox.square(
            dimension: 7,
            child: DecoratedBox(
              decoration: BoxDecoration(color: dot, shape: BoxShape.circle),
            ),
          ),
          const SizedBox(width: 6),
          Text(
            text,
            style: JomhorType.caption.copyWith(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: onPaper ? JomhorColors.ticketInk : JomhorColors.textSecondary,
            ),
          ),
        ],
      ),
    );
  }
}

/// Under the card: "Porte 4" + its load chip + "Itinéraire" (flips the card), and the "plus fluide" hint.
class GateRow extends StatelessWidget {
  const new({required this.ticket, required this.load, required this.now, required this.onRoute, super.key});

  final FanTicket ticket;
  final GateLoad? load;
  final DateTime now;
  final VoidCallback onRoute;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final gates = ticket.shownGates;
    if (gates.isEmpty) return const SizedBox.shrink();
    final yours = gates.first;
    final smoother = smootherGate(gates, load, now);
    return Padding(
      padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.m, JomhorSpace.xl, 0),
      child: Container(
        key: const Key('gate.row'),
        padding: const EdgeInsetsDirectional.fromSTEB(JomhorSpace.m, JomhorSpace.m, JomhorSpace.s, JomhorSpace.m),
        decoration: const BoxDecoration(
          color: JomhorColors.surface,
          border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
          borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
        ),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: const BoxDecoration(
                color: JomhorColors.ground,
                borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.chip)),
              ),
              child: const Icon(Icons.place_outlined, size: 20, color: JomhorColors.accent),
            ),
            const SizedBox(width: JomhorSpace.m),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    spacing: JomhorSpace.s,
                    runSpacing: JomhorSpace.xs,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      Text(
                        gateName(l, yours.code),
                        style: JomhorType.body.copyWith(fontWeight: FontWeight.w600, fontSize: 14),
                      ),
                      LoadChip(load?.levelOf(yours.code, now) ?? GateLevel.unknown),
                    ],
                  ),
                  if (smoother != null) ...[
                    const SizedBox(height: 2),
                    Text(
                      l.gateSmoother(l.ltr(smoother.code)),
                      key: const Key('gate.smoother'),
                      style: JomhorType.caption.copyWith(fontSize: 12, color: JomhorColors.textMuted),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: JomhorSpace.s),
            // Large text: the label wraps rather than pushing the row off screen.
            Flexible(
              child: TextButton.icon(
                key: const Key('gate.route'),
                onPressed: onRoute,
                style: TextButton.styleFrom(
                  minimumSize: const Size(0, JomhorSize.touchMin),
                  foregroundColor: JomhorColors.accent,
                  padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.m),
                ),
                icon: const Icon(Icons.directions_outlined, size: 18),
                label: Text(l.gateRoute),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The gate side of the card: the gate code in display 72, its name, the directions, "Ouvrir dans Plans" when the
/// gate has coordinates, the zone's other gates with their chips and "Retourner le billet". No QR on this side.
class GateFace extends StatelessWidget {
  const new({
    required this.ticket,
    required this.load,
    required this.now,
    required this.matchday,
    required this.onBack,
    required this.onMaps,
    super.key,
  });

  final FanTicket ticket;
  final GateLoad? load;
  final DateTime now;
  final bool matchday;
  final VoidCallback onBack;
  final ValueChanged<Uri> onMaps;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final gates = ticket.shownGates;
    final e = ticket.event;
    final muted = const TextStyle(
      fontFamily: JomhorFonts.body,
      fontSize: 11,
      fontWeight: FontWeight.w600,
      color: JomhorColors.ticketMuted,
      letterSpacing: 1.2,
    ).tracked(context);
    final yours = gates.firstOrNull;
    final maps = yours == null ? null : mapsUrl(yours, defaultTargetPlatform);
    return Semantics(
      container: true,
      label: yours == null ? null : l.gateSideLabel(yours.code),
      child: TicketPaper(
        matchday: matchday,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TicketBand(
              crestKey: const Key('gate.crest'),
              overline: switch (e.venueCity) {
                final city? => l.dotPair(l.isolate(e.venueName), l.isolate(city)),
                null => l.isolate(e.venueName),
              },
              title: e.organizer?.name ?? e.title,
              club: e.organizer,
            ),
            if (yours != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, 18, JomhorSpace.xl, 0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l.ticketGates(1).toUpperCase(), style: muted),
                    Row(
                      children: [
                        Text(
                          yours.code,
                          key: const Key('gate.code'),
                          textDirection: TextDirection.ltr,
                          style: JomhorType.scanResult.copyWith(color: JomhorColors.ticketInk),
                        ),
                        const SizedBox(width: JomhorSpace.l),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              if (yours.name case final name?)
                                Text(
                                  l.isolate(name),
                                  style: JomhorType.h2.copyWith(color: JomhorColors.ticketInk, fontSize: 20),
                                ),
                              const SizedBox(height: 6),
                              LoadChip(load?.levelOf(yours.code, now) ?? GateLevel.unknown, onPaper: true),
                            ],
                          ),
                        ),
                      ],
                    ),
                    if (yours.directions case final directions?) ...[
                      const SizedBox(height: JomhorSpace.s),
                      Text(
                        l.isolate(directions),
                        key: const Key('gate.directions'),
                        style: JomhorType.body.copyWith(
                          color: JomhorColors.ticketInk,
                          fontWeight: FontWeight.w400,
                          height: 1.4,
                        ),
                      ),
                    ],
                    if (maps != null) ...[
                      const SizedBox(height: JomhorSpace.l),
                      FilledButton.icon(
                        key: const Key('gate.maps'),
                        onPressed: () => onMaps(maps),
                        style: FilledButton.styleFrom(
                          backgroundColor: JomhorColors.ticketInk,
                          foregroundColor: JomhorColors.text,
                          minimumSize: const Size.fromHeight(48),
                          shape: const RoundedRectangleBorder(
                            borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
                          ),
                        ),
                        icon: const Icon(Icons.map_outlined, size: 20),
                        label: Text(l.gateOpenMaps),
                      ),
                    ],
                  ],
                ),
              ),
            const Perforation(),
            if (gates.length > 1)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.xl),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l.gateOthers.toUpperCase(), style: muted),
                    for (final g in gates.skip(1))
                      Padding(
                        key: Key('gate.other.${g.code}'),
                        padding: const EdgeInsets.only(top: JomhorSpace.s),
                        child: Row(
                          children: [
                            Expanded(
                              child: Text.rich(
                                _otherGate(l, g),
                                style: JomhorType.body.copyWith(color: JomhorColors.ticketInk, fontSize: 14),
                              ),
                            ),
                            LoadChip(load?.levelOf(g.code, now) ?? GateLevel.unknown, onPaper: true),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
            const Spacer(),
            Padding(
              padding: const EdgeInsets.fromLTRB(JomhorSpace.l, JomhorSpace.s, JomhorSpace.l, JomhorSpace.l),
              child: TextButton.icon(
                key: const Key('gate.back'),
                onPressed: onBack,
                style: TextButton.styleFrom(
                  foregroundColor: JomhorColors.ticketInk,
                  minimumSize: const Size.fromHeight(JomhorSize.touchMin),
                ),
                icon: const Icon(Icons.qr_code_2_rounded, size: 20),
                label: Text(l.gateFlipBack),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
