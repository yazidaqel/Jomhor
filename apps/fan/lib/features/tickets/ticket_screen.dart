import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_core/jomhor_core.dart' show slotAt;
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/sale_copy.dart';
import 'package:jomhor_fan/features/tickets/pass_hero.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:screen_brightness/screen_brightness.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

/// Status copy for a ticket that shows no QR (no K_t: not VALID, or the event is closed).
String ticketStatusText(AppLocalizations l, FanTicket t) => switch (t.status) {
  'VALID' => l.ticketClosed,
  'CANCELLED' => l.ticketCancelled,
  'REFUNDED' => l.ticketRefunded,
  'BLOCKED' => l.ticketBlocked,
  _ => l.ticketNotValid,
};

/// "3, 4" for the gate codes, with the locale's list separator.
String gatesText(AppLocalizations l, FanTicket t) => t.gateCodes.join(l.listSeparator);

/// Screen 5 · Mon billet (DESIGN.md + Lot 1 adjustments). Reads the saved tickets only, so it works in airplane
/// mode; its one network call is the WhatsApp re-send, on a tap. Full brightness and screen kept awake while shown.
class TicketScreen extends ConsumerStatefulWidget {
  const new({required this.id, this.origin, super.key});

  final String id;

  /// The card the fan tapped, for the hero flight; null for a deep link or the matchday auto-open.
  final PassOrigin? origin;

  @override
  ConsumerState<TicketScreen> createState() => _TicketScreenState();
}

class _TicketScreenState extends ConsumerState<TicketScreen> {
  PageController? _pages;
  var _page = 0;
  Timer? _tick;

  /// QR slot of the last tick: a change is a new code, felt as a haptic tick.
  int? _slot;

  @override
  void initState() {
    super.initState();
    unawaited(_glow(on: true));
    _schedule();
  }

  /// Redraws on each server-time second: the QR changes right at each 30 s boundary, and the matchday label
  /// appears when T0 - 4 h is crossed while the screen is open.
  void _schedule() {
    final now = ref.read(ticketsProvider).value?.snapshot?.clock.now();
    final ms = now == null ? 0 : now.millisecondsSinceEpoch % 1000;
    _tick = Timer(Duration(milliseconds: 1000 - ms), () {
      if (!mounted) return;
      _tickHaptic();
      setState(() {});
      _schedule();
    });
  }

  void _tickHaptic() {
    final snapshot = ref.read(ticketsProvider).value?.snapshot;
    final shows = snapshot?.tickets.any((t) => t.id == widget.id && t.hasQr) ?? false;
    final slot = shows ? slotAt(snapshot!.clock.now()) : null;
    if (slot != null && _slot != null && slot != _slot) unawaited(HapticFeedback.selectionClick());
    _slot = slot;
  }

  @override
  void dispose() {
    _tick?.cancel();
    unawaited(_glow(on: false));
    _pages?.dispose();
    super.dispose();
  }

  /// A device without these controls (or a test) still shows the ticket.
  static Future<void> _glow({required bool on}) async {
    try {
      final brightness = ScreenBrightness.instance;
      await (on ? brightness.setApplicationScreenBrightness(1) : brightness.resetApplicationScreenBrightness());
    } on Object {
      // Brightness stays as the fan set it.
    }
    try {
      await WakelockPlus.toggle(enable: on);
    } on Object {
      // The screen may sleep; the QR is back as soon as it wakes.
    }
  }

  void _close() => context.canPop() ? context.pop() : context.go('/tickets');

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final tickets = ref.watch(ticketsProvider);
    final snapshot = tickets.value?.snapshot;
    final ticket = snapshot?.tickets.where((t) => t.id == widget.id).firstOrNull;

    if (snapshot == null || ticket == null) {
      return Scaffold(
        body: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _Header(onClose: _close, matchday: false),
              Expanded(
                child: tickets.isLoading && snapshot == null
                    ? const Padding(
                        padding: EdgeInsets.all(JomhorSpace.xl),
                        child: Skeleton(height: 560, radius: JomhorRadius.cardLarge),
                      )
                    : Center(child: StatePanel(message: l.ticketNotFound)),
              ),
            ],
          ),
        ),
      );
    }

    final group = snapshot.ofEvent(ticket.event.id);
    final pages = _pages ??= PageController(initialPage: _page = group.indexOf(ticket));
    final shown = group[_page.clamp(0, group.length - 1)];
    final now = snapshot.clock.now();
    final matchday = isMatchday(shown, now);
    return Scaffold(
      body: Stack(
        children: [
          // Matchday mode: the stadium light comes on behind the pass.
          PositionedDirectional(
            top: -170,
            start: -150,
            child: AnimatedOpacity(
              opacity: matchday ? 1 : 0,
              duration: motionOf(context, JomhorMotion.ticketFlip),
              child: const AccentGlow(size: 480, opacity: 0.26),
            ),
          ),
          SafeArea(
            child: Column(
              children: [
                _Header(onClose: _close, matchday: matchday),
                Expanded(
                  child: PageView(
                    controller: pages,
                    onPageChanged: (i) => setState(() => _page = i),
                    children: [
                      for (final t in group)
                        SingleChildScrollView(
                          padding: const EdgeInsets.fromLTRB(
                            JomhorSpace.xl,
                            JomhorSpace.s,
                            JomhorSpace.xl,
                            JomhorSpace.l,
                          ),
                          child: switch (widget.origin) {
                            final origin? when t.id == widget.id && !reduceMotion(context) => Hero(
                              tag: passHeroTag(origin, t.id),
                              flightShuttleBuilder: passFlight,
                              child: _TicketCard(ticket: t, now: now, matchday: matchday),
                            ),
                            _ => _TicketCard(ticket: t, now: now, matchday: matchday),
                          },
                        ),
                    ],
                  ),
                ),
                if (group.length > 1) _Dots(count: group.length, current: _page),
                _WhatsApp(ticket: shown),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const new({required this.onClose, required this.matchday});

  final VoidCallback onClose;
  final bool matchday;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(JomhorSpace.l, JomhorSpace.s, JomhorSpace.l, JomhorSpace.s),
      child: Row(
        children: [
          IconButton(onPressed: onClose, tooltip: l.close, icon: const Icon(Icons.close_rounded, size: 20)),
          Expanded(
            child: AnimatedSwitcher(
              duration: motionOf(context, JomhorMotion.base),
              child: matchday
                  ? Row(
                      key: const Key('ticket.matchday'),
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const SizedBox.square(
                          dimension: 8,
                          child: DecoratedBox(
                            decoration: BoxDecoration(color: JomhorColors.accent, shape: BoxShape.circle),
                          ),
                        ),
                        const SizedBox(width: JomhorSpace.s),
                        Flexible(
                          child: Text(
                            l.ticketMatchday.toUpperCase(),
                            textAlign: TextAlign.center,
                            style: JomhorType.label.copyWith(color: JomhorColors.accent),
                          ),
                        ),
                      ],
                    )
                  : const SizedBox.shrink(),
            ),
          ),
          // Not a control: says the brightness is forced to max while the ticket is shown.
          Tooltip(
            message: l.ticketBrightness,
            child: Container(
              width: 48,
              height: 48,
              alignment: Alignment.center,
              child: Container(
                width: JomhorSize.touchMin,
                height: JomhorSize.touchMin,
                decoration: const BoxDecoration(
                  color: JomhorColors.surface,
                  shape: BoxShape.circle,
                  border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
                ),
                child: const Icon(Icons.light_mode_outlined, size: 20),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _TicketCard extends StatelessWidget {
  const new({required this.ticket, required this.now, required this.matchday});

  final FanTicket ticket;

  /// Server time of this frame.
  final DateTime now;
  final bool matchday;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final t = ticket;
    final e = t.event;
    final label = factsLine(l, e.facts);
    const muted = TextStyle(
      fontFamily: JomhorFonts.body,
      fontSize: 11,
      fontWeight: FontWeight.w600,
      color: JomhorColors.ticketMuted,
      letterSpacing: 1.2,
    );
    final value = JomhorType.h2.copyWith(fontSize: 26, height: 1, color: JomhorColors.ticketInk);
    const strong = TextStyle(
      fontFamily: JomhorFonts.body,
      fontWeight: FontWeight.w600,
      fontSize: 15,
      color: JomhorColors.ticketInk,
    );

    Widget fact(String name, String text, [String? caption]) => Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(name.toUpperCase(), style: muted),
        const SizedBox(height: 4),
        Text(text, style: value),
        if (caption != null) ...[
          const SizedBox(height: 2),
          Text(caption, style: muted.copyWith(letterSpacing: 0, fontWeight: FontWeight.w500)),
        ],
      ],
    );

    return AnimatedContainer(
      duration: motionOf(context, JomhorMotion.ticketFlip),
      decoration: BoxDecoration(
        color: JomhorColors.ticketPaper,
        borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.cardLarge)),
        boxShadow: [
          BoxShadow(
            color: matchday ? JomhorColors.accent.withValues(alpha: 0.5) : JomhorColors.ground.withValues(alpha: 0),
            blurRadius: 36,
            spreadRadius: 2,
          ),
        ],
      ),
      child: ClipRRect(
        borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.cardLarge)),
        child: DefaultTextStyle.merge(
          style: const TextStyle(color: JomhorColors.ticketInk),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Accent band: competition, title, crest.
              ColoredBox(
                color: JomhorColors.accent,
                child: Padding(
                  padding: const EdgeInsetsDirectional.fromSTEB(JomhorSpace.xl, 18, JomhorSpace.l, JomhorSpace.l),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (label != null) ...[
                              Text(
                                label.toUpperCase(),
                                style: JomhorType.caption.copyWith(
                                  color: JomhorColors.onAccent.withValues(alpha: 0.72),
                                  letterSpacing: 1.5,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              const SizedBox(height: 6),
                            ],
                            Text(
                              e.title.toUpperCase(),
                              style: JomhorType.hero.copyWith(fontSize: 29, color: JomhorColors.onAccent),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: JomhorSpace.m),
                      Container(
                        width: JomhorSize.touchMin,
                        height: JomhorSize.touchMin,
                        alignment: Alignment.center,
                        decoration: const BoxDecoration(color: JomhorColors.ground, shape: BoxShape.circle),
                        child: Text(
                          JomhorClub.crestInitial,
                          textScaler: TextScaler.noScaling,
                          style: JomhorType.number.copyWith(color: JomhorColors.accent, fontSize: 24),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, 18, JomhorSpace.xl, 0),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(flex: 4, child: fact(l.ticketZone, t.zone.name)),
                    const SizedBox(width: JomhorSpace.m),
                    Expanded(flex: 2, child: fact(l.ticketGates(t.gateCodes.length), gatesText(l, t))),
                    const SizedBox(width: JomhorSpace.m),
                    Expanded(
                      flex: 3,
                      child: fact(
                        e.facts is FootballFacts ? l.ticketKickoff : l.ticketStart,
                        venueClock(e.startsAt, e.timezone, l.localeName),
                        venueDate(e.startsAt, e.timezone, l.localeName),
                      ),
                    ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.l, JomhorSpace.xl, JomhorSpace.xs),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          if (t.holderLabel case final holder?) ...[
                            Text(l.ticketHolder.toUpperCase(), style: muted),
                            const SizedBox(height: 4),
                            Text(holder, style: strong),
                          ],
                          if (t.seatLabel case final seat?) ...[
                            const SizedBox(height: JomhorSpace.s),
                            Text(l.ticketSeat.toUpperCase(), style: muted),
                            const SizedBox(height: 4),
                            Text(seat, style: strong),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(width: JomhorSpace.m),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(l.ticketNumber.toUpperCase(), style: muted),
                          const SizedBox(height: 4),
                          Text(
                            t.ticketNo,
                            textDirection: TextDirection.ltr,
                            textAlign: TextAlign.end,
                            style: JomhorType.h2.copyWith(
                              fontSize: 20,
                              color: JomhorColors.ticketInk,
                              letterSpacing: 1.5,
                              height: 1.05,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const _Perforation(),
              Padding(
                padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.xs, JomhorSpace.xl, JomhorSpace.xl),
                child: t.hasQr
                    ? RotatingQr(ticket: t, now: now)
                    : Padding(
                        padding: const EdgeInsets.symmetric(vertical: JomhorSpace.xxl),
                        child: Column(
                          children: [
                            const Icon(Icons.qr_code_2_rounded, size: 56, color: JomhorColors.ticketRule),
                            const SizedBox(height: JomhorSpace.m),
                            Text(
                              ticketStatusText(l, t),
                              key: const Key('ticket.status'),
                              textAlign: TextAlign.center,
                              style: JomhorType.h2.copyWith(color: JomhorColors.ticketInk),
                            ),
                          ],
                        ),
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Two ground-coloured notches and a dashed rule between the facts and the QR.
class _Perforation extends StatelessWidget {
  const new();

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 28,
    child: Stack(
      clipBehavior: Clip.none,
      alignment: Alignment.center,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.xxl),
          child: LayoutBuilder(
            builder: (context, box) => Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                for (var i = 0; i < box.maxWidth ~/ 10; i++)
                  const SizedBox(width: 5, height: 2, child: ColoredBox(color: JomhorColors.ticketRule)),
              ],
            ),
          ),
        ),
        for (final side in [-1.0, 1.0])
          Align(
            alignment: Alignment(side, 0),
            child: FractionalTranslation(
              translation: Offset(side / 2, 0),
              child: const SizedBox.square(
                dimension: 28,
                child: DecoratedBox(
                  decoration: BoxDecoration(color: JomhorColors.ground, shape: BoxShape.circle),
                ),
              ),
            ),
          ),
      ],
    ),
  );
}

/// The v2 QR for the slot of [now] (server time), with the time left until the next code.
class RotatingQr extends StatelessWidget {
  const new({required this.ticket, required this.now, super.key});

  final FanTicket ticket;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final left = secondsToNextSlot(now);
    // DESIGN.md §Accessibility: the QR scales down to 160 px with large text so the card does not clip.
    final size = MediaQuery.textScalerOf(context).scale(1) > 1.15 ? 160.0 : 200.0;
    const tabular = [FontFeature.tabularFigures()];
    return Column(
      children: [
        // Nothing moves over the QR (DESIGN.md principle 6); it repaints alone each second.
        RepaintBoundary(
          child: TicketQr(data: ticket.rotatingToken(now)!, size: size, label: l.ticketQrLabel(ticket.ticketNo)),
        ),
        const SizedBox(height: 14),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            RepaintBoundary(
              child: SizedBox(
                width: 120,
                height: 5,
                child: DecoratedBox(
                  decoration: const BoxDecoration(
                    color: JomhorColors.ticketRule,
                    borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.pill)),
                  ),
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: AnimatedFractionallySizedBox(
                      key: const Key('ticket.progress'),
                      duration: motionOf(context, 300),
                      curve: Curves.easeOut,
                      widthFactor: left / JomhorMotion.qrRotationSeconds,
                      heightFactor: 1,
                      child: const DecoratedBox(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(colors: [JomhorColors.accent, JomhorColors.accentHover]),
                          borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.pill)),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(width: 10),
            Flexible(
              child: Text(
                l.ticketNextCode(left),
                style: const TextStyle(
                  fontFamily: JomhorFonts.body,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                  color: JomhorColors.ticketMuted,
                  fontFeatures: tabular,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.check_rounded, size: 16, color: JomhorColors.success),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                l.ticketWorksOffline,
                style: const TextStyle(
                  fontFamily: JomhorFonts.body,
                  fontSize: 12,
                  color: JomhorColors.success,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

/// High-contrast QR (ink on paper), error correction M (docs/02 §Ticket token & QR format).
class TicketQr extends StatelessWidget {
  const new({required this.data, required this.size, required this.label, super.key});

  final String data;
  final double size;
  final String label;

  @override
  Widget build(BuildContext context) => QrImageView(
    data: data,
    size: size,
    padding: EdgeInsets.zero,
    errorCorrectionLevel: QrErrorCorrectLevel.M,
    backgroundColor: JomhorColors.ticketPaper,
    eyeStyle: const QrEyeStyle(eyeShape: QrEyeShape.square, color: JomhorColors.ticketInk),
    dataModuleStyle: const QrDataModuleStyle(dataModuleShape: QrDataModuleShape.square, color: JomhorColors.ticketInk),
    semanticsLabel: label,
  );
}

class _Dots extends StatelessWidget {
  const new({required this.count, required this.current});

  final int count;
  final int current;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: JomhorSpace.xs),
    child: Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        for (var i = 0; i < count; i++)
          AnimatedContainer(
            duration: motionOf(context, JomhorMotion.base),
            curve: Curves.easeOut,
            width: i == current ? 22 : 8,
            height: 8,
            margin: const EdgeInsets.symmetric(horizontal: 3),
            decoration: BoxDecoration(
              borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.pill)),
              color: i == current ? JomhorColors.accent : JomhorColors.borderMuted,
            ),
          ),
      ],
    ),
  );
}

/// Lot 1 action: WhatsApp re-send of the PDF, for a VALID ticket. The call runs only on a tap.
class _WhatsApp extends ConsumerStatefulWidget {
  const new({required this.ticket});

  final FanTicket ticket;

  @override
  ConsumerState<_WhatsApp> createState() => _WhatsAppState();
}

class _WhatsAppState extends ConsumerState<_WhatsApp> {
  var _sending = false;

  Future<void> _send() async {
    final l = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _sending = true);
    final result = await ref.read(resendWhatsAppProvider)(widget.ticket.id);
    if (mounted) setState(() => _sending = false);
    messenger.showSnackBar(
      SnackBar(
        content: Text(switch (result) {
          Resend.sent => l.ticketWhatsAppSent,
          Resend.rateLimited => l.ticketWhatsAppRateLimited,
          Resend.notValid => l.ticketWhatsAppNotValid,
          Resend.offline => l.errorNetwork,
          Resend.failed => l.ticketWhatsAppFailed,
        }),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final valid = widget.ticket.status == 'VALID';
    return Padding(
      padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.m, JomhorSpace.xl, JomhorSpace.l),
      child: Column(
        children: [
          ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 56),
            child: OutlinedButton.icon(
              key: const Key('ticket.whatsapp'),
              onPressed: valid && !_sending ? _send : null,
              icon: _sending
                  ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.send_rounded, size: 20),
              label: Text(l.ticketWhatsApp),
            ),
          ),
          const SizedBox(height: JomhorSpace.s),
          Text(
            l.ticketWhatsAppHint,
            textAlign: TextAlign.center,
            style: JomhorType.caption.copyWith(color: JomhorColors.textMuted, fontSize: 12),
          ),
        ],
      ),
    );
  }
}
