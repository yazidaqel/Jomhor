import 'dart:async';
import 'dart:math' show pi;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_core/jomhor_core.dart' show slotAt;
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/sale_copy.dart';
import 'package:jomhor_fan/features/tickets/gate_load.dart';
import 'package:jomhor_fan/features/tickets/gate_side.dart';
import 'package:jomhor_fan/features/tickets/pass_hero.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/features/tickets/transfer.dart';
import 'package:jomhor_fan/features/tickets/transfer_sheet.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:screen_brightness/screen_brightness.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

/// Status copy for a ticket that shows no QR (no K_t: not VALID, or the event is closed).
String ticketStatusText(AppLocalizations l, FanTicket t) => switch (t.status) {
  _ when t.transferred != null => transferredText(l, t, t.transferred!),
  'VALID' => l.ticketClosed,
  'CANCELLED' => l.ticketCancelled,
  'REFUNDED' => l.ticketRefunded,
  'BLOCKED' => l.ticketBlocked,
  _ => l.ticketNotValid,
};

/// "3, 4" for the gate codes, with the locale's list separator.
String gatesText(AppLocalizations l, FanTicket t) => t.gateCodes.join(l.listSeparator);

/// Screen 5 · Mon billet (DESIGN.md + Lot 1 and Lot 4 adjustments). Reads the saved tickets, so the QR, the gate
/// side and the directions work in airplane mode. Network calls: the WhatsApp re-send, on a tap, and on matchday the
/// gate load poll (`/fan/gates/{event}/load`: chips and "Tu es dedans"), only while visible, from T0 - 3 h to
/// T0 + 1 h, every 60 s ± 10 s, until the shown ticket is entered. Full brightness and screen kept awake while shown.
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

  /// The ticket just given away, as it was: it slides out of the screen before the wallet shows its new row.
  FanTicket? _gone;

  /// Tickets showing their gate side.
  final _gateSide = <String>{};

  /// Tickets entered while this screen is open: they flip once to "Tu es dedans". The others open on it.
  final _entering = <String>{};

  /// No sheet over this screen (read in build).
  var _current = true;

  @override
  void initState() {
    super.initState();
    unawaited(_glow(on: true));
    _schedule();
    WidgetsBinding.instance.addPostFrameCallback((_) => _maybePoll());
  }

  /// Redraws on each server-time second: the QR changes right at each 30 s boundary, and the matchday label
  /// appears when T0 - 4 h is crossed while the screen is open.
  void _schedule() {
    final now = ref.read(ticketsProvider).value?.snapshot?.clock.now();
    final ms = now == null ? 0 : now.millisecondsSinceEpoch % 1000;
    _tick = Timer(Duration(milliseconds: 1000 - ms), () {
      if (!mounted) return;
      _tickHaptic();
      _maybePoll();
      setState(() {});
      _schedule();
    });
  }

  /// The ticket of the current page.
  FanTicket? _shown() {
    final snapshot = ref.read(ticketsProvider).value?.snapshot;
    final ticket = snapshot?.tickets.where((t) => t.id == widget.id).firstOrNull;
    if (snapshot == null || ticket == null) return null;
    final group = snapshot.ofEvent(ticket.event.id);
    return group[_page.clamp(0, group.length - 1)];
  }

  bool get _visible {
    final app = WidgetsBinding.instance.lifecycleState;
    return mounted && _current && (app == null || app == AppLifecycleState.resumed);
  }

  /// On each tick: the event's [GateLoads] decides whether a gate load poll is due.
  void _maybePoll() {
    final shown = _shown();
    final now = ref.read(ticketsProvider).value?.snapshot?.clock.now();
    if (shown != null && now != null) unawaited(_poll(shown, now));
  }

  Future<void> _poll(FanTicket shown, DateTime now) async {
    final fresh = await ref.read(gateLoadProvider(shown.event.id).notifier).pollIfDue(shown, now, visible: _visible);
    if (!mounted || fresh.isEmpty) return;
    setState(() {
      _entering.addAll(fresh);
      _gateSide.removeAll(fresh);
    });
    // The stadium tells the fan they made it.
    if (fresh.contains(_shown()?.id)) unawaited(HapticFeedback.heavyImpact());
  }

  void _flip(String ticketId) => setState(() {
    if (!_gateSide.remove(ticketId)) _gateSide.add(ticketId);
  });

  /// The card of [t] with its faces (three while it flips to "Tu es dedans"), all as tall as the tallest.
  Widget _card(FanTicket t, DateTime now, bool matchday, Map<String, Entry> entered) {
    final entry = entered[t.id];
    final gate = t.hasQr && _gone == null ? () => _flip(t.id) : null;
    Widget front(Entry? e) => _TicketCard(ticket: t, now: now, matchday: matchday, entry: e, onGate: gate);
    final entering = entry != null && _entering.contains(t.id);
    return IntrinsicHeight(
      child: _Flip(
        shown: _gateSide.contains(t.id) ? 'gate' : (entering ? 'entered' : 'front'),
        faces: {
          // Opened on an entered ticket: the entered face is the front, with no flip.
          'front': front(entering ? null : entry),
          'entered': ?(entering ? front(entry) : null),
          if (t.hasQr)
            'gate': GateFace(
              ticket: t,
              load: ref.watch(gateLoadProvider(t.event.id)),
              now: now,
              matchday: matchday,
              onBack: () => _flip(t.id),
              onMaps: (url) => unawaited(ref.read(openMapsProvider)(url)),
            ),
        },
      ),
    );
  }

  void _tickHaptic() {
    final snapshot = ref.read(ticketsProvider).value?.snapshot;
    // An entered ticket shows "Tu es dedans" instead of its QR: no tick for a code nobody sees.
    final entered = ref.read(enteredProvider).value?.containsKey(widget.id) ?? false;
    final shows = !entered && (snapshot?.tickets.any((t) => t.id == widget.id && t.hasQr) ?? false);
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

  /// Screen 8, then on a 201 the slide-out (the reverse of the purchase slide-in; none under reduced motion) and the
  /// wallet, where the muted "Transféré à" row now stands.
  Future<void> _transfer(FanTicket t) async {
    final l = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final done = await showTransferSheet(context, t);
    if (done == null || !mounted) return;
    final leave = motionOf(context, JomhorMotion.ticketFlip);
    setState(() => _gone = t);
    await Future<void>.delayed(leave);
    if (!mounted) return;
    context.go('/tickets');
    if (done.to.toLabel case final name?) {
      messenger.showSnackBar(SnackBar(content: Text(l.transferDone(l.isolate(name)))));
    }
  }

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
    final entered = ref.watch(enteredProvider).value ?? const <String, Entry>{};
    final shownEntered = entered.containsKey(shown.id);
    _current = ModalRoute.isCurrentOf(context) ?? true;
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
                  child: AnimatedSlide(
                    key: const Key('ticket.leave'),
                    offset: _gone == null ? Offset.zero : const Offset(0, -0.85),
                    duration: motionOf(context, JomhorMotion.ticketFlip),
                    curve: Curves.easeInCubic,
                    child: AnimatedOpacity(
                      opacity: _gone == null ? 1 : 0,
                      duration: motionOf(context, JomhorMotion.ticketFlip),
                      curve: Curves.easeIn,
                      child: PageView(
                        controller: pages,
                        physics: _gone == null ? null : const NeverScrollableScrollPhysics(),
                        onPageChanged: (i) => setState(() => _page = i),
                        children: [
                          // The leaving card keeps its face (QR included) while it slides out.
                          for (final t in group.map((held) => held.id == _gone?.id ? _gone! : held))
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
                                  child: _card(t, now, matchday, entered),
                                ),
                                _ => _card(t, now, matchday, entered),
                              },
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
                if (group.length > 1) _Dots(count: group.length, current: _page),
                if (shown.hasQr && !shownEntered && _gone == null)
                  // The gate side already says all of it: the row fades out while it is up (keeping its room, so
                  // the card does not move).
                  _Fade(
                    key: const Key('gate.rowFade'),
                    shown: !_gateSide.contains(shown.id),
                    child: GateRow(
                      ticket: shown,
                      load: ref.watch(gateLoadProvider(shown.event.id)),
                      now: now,
                      onRoute: () => _flip(shown.id),
                    ),
                  ),
                _Actions(
                  ticket: _gone ?? shown,
                  now: now,
                  entered: shownEntered,
                  onTransfer: _gone == null ? () => _transfer(shown) : null,
                ),
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
                            style: JomhorType.label.copyWith(color: JomhorColors.accent).tracked(context),
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

/// The paper of a card face: radius 28, the matchday glow behind it. Each face carries its own, so it turns with it.
class TicketPaper extends StatelessWidget {
  const new({required this.matchday, required this.child, super.key});

  final bool matchday;
  final Widget child;

  @override
  Widget build(BuildContext context) => AnimatedContainer(
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
        child: child,
      ),
    ),
  );
}

/// The band of a card face: the Jomhor mark (all ink on amber, far from the QR), [overline], [title] and the ticket's
/// club crest. Amber, or `success` once the ticket is entered. The club is content here, never a colour.
class TicketBand extends StatelessWidget {
  const new({
    required this.title,
    this.overline,
    this.club,
    this.success = false,
    this.crestKey = const Key('ticket.crest'),
    this.titleMaxLines,
    super.key,
  });

  final String title;

  /// One line on the member card (screen 7), whose height is fixed; the ticket wraps.
  final int? titleMaxLines;
  final String? overline;
  final Club? club;
  final bool success;
  final Key crestKey;

  @override
  Widget build(BuildContext context) {
    final ink = success ? JomhorColors.text : JomhorColors.onAccent;
    return ColoredBox(
      key: Key(success ? 'ticket.band.success' : 'ticket.band'),
      color: success ? JomhorColors.success : JomhorBrand.amber,
      child: Padding(
        padding: const EdgeInsetsDirectional.fromSTEB(JomhorSpace.xl, JomhorSpace.m, JomhorSpace.l, JomhorSpace.l),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      JomhorMark(size: 28, onAmber: !success),
                      if (overline case final text?)
                        Expanded(
                          child: Text(
                            text.toUpperCase(),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: JomhorType.caption
                                .copyWith(
                                  color: ink.withValues(alpha: 0.72),
                                  letterSpacing: 1.5,
                                  fontWeight: FontWeight.w600,
                                )
                                .tracked(context),
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 2),
                  Text(
                    title.toUpperCase(),
                    maxLines: titleMaxLines,
                    overflow: titleMaxLines == null ? null : TextOverflow.ellipsis,
                    style: JomhorType.hero.copyWith(fontSize: 29, color: ink),
                  ),
                ],
              ),
            ),
            // The ticket's club (a cache saved before multi-club has none: no crest then).
            if (club case final club?) ...[
              const SizedBox(width: JomhorSpace.m),
              Crest(
                key: crestKey,
                name: club.name,
                url: club.crestUrl,
                size: JomhorSize.touchMin,
                disc: JomhorColors.ground,
                ink: JomhorColors.accent,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// The front of the card: band, facts, holder, perforation and the rotating QR; once [entry] is set, "Tu es dedans"
/// on a `success` band in place of the QR (DESIGN.md Lot 4, screen 5 entry).
class _TicketCard extends StatelessWidget {
  const new({required this.ticket, required this.now, required this.matchday, this.entry, this.onGate});

  final FanTicket ticket;

  /// Server time of this frame.
  final DateTime now;
  final bool matchday;
  final Entry? entry;

  /// The PORTE fact flips the card to its gate side; null when there is none (no QR).
  final VoidCallback? onGate;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final t = ticket;
    final e = t.event;
    final label = factsLine(l, e.facts);
    final muted = const TextStyle(
      fontFamily: JomhorFonts.body,
      fontSize: 11,
      fontWeight: FontWeight.w600,
      color: JomhorColors.ticketMuted,
      letterSpacing: 1.2,
    ).tracked(context);
    final value = JomhorType.h2.copyWith(fontSize: 26, height: 1, color: JomhorColors.ticketInk);
    const strong = TextStyle(
      fontFamily: JomhorFonts.body,
      fontWeight: FontWeight.w600,
      fontSize: 15,
      color: JomhorColors.ticketInk,
    );

    Widget fact(String name, String text, {String? caption, String? extra, bool chevron = false}) => Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(name.toUpperCase(), style: muted),
        const SizedBox(height: 4),
        Row(
          children: [
            Flexible(child: Text(text, style: value)),
            if (extra != null) ...[
              const SizedBox(width: JomhorSpace.xs),
              Text(extra, key: const Key('ticket.gateMore'), style: muted.copyWith(fontSize: 13, letterSpacing: 0)),
            ],
            if (chevron) const Icon(Icons.chevron_right_rounded, size: 20, color: JomhorColors.ticketMuted),
          ],
        ),
        if (caption != null) ...[
          const SizedBox(height: 2),
          Text(caption, style: muted.copyWith(letterSpacing: 0, fontWeight: FontWeight.w500)),
        ],
      ],
    );

    // Your gate only (lot-4 Q81), on one line; "+2" when the zone has more: the gate side lists them.
    final yours = t.shownGates.firstOrNull;
    final more = t.shownGates.length - 1;
    final gates = fact(
      l.ticketGates(1),
      yours?.code ?? '',
      extra: more > 0 ? l.ltr(l.gateMore(more)) : null,
      chevron: onGate != null,
    );
    return TicketPaper(
      matchday: matchday,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TicketBand(overline: label, title: e.title, club: e.organizer, success: entry != null),
          Padding(
            padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, 18, JomhorSpace.xl, 0),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(flex: 4, child: fact(l.ticketZone, t.zone.name)),
                const SizedBox(width: JomhorSpace.m),
                Expanded(
                  flex: 2,
                  child: switch (onGate) {
                    // The PORTE fact is the way to the gate side: a 44 px button.
                    final flip? => Semantics(
                      button: true,
                      label: l.gateShow(gateName(l, yours?.code ?? '')),
                      excludeSemantics: true,
                      onTap: flip,
                      child: InkWell(
                        key: const Key('ticket.gate'),
                        onTap: flip,
                        borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.chip)),
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(minHeight: JomhorSize.touchMin),
                          child: gates,
                        ),
                      ),
                    ),
                    null => gates,
                  },
                ),
                const SizedBox(width: JomhorSpace.m),
                Expanded(
                  flex: 3,
                  child: fact(
                    e.facts is FootballFacts ? l.ticketKickoff : l.ticketStart,
                    venueClock(e.startsAt, e.timezone, l.localeName),
                    caption: venueDate(e.startsAt, e.timezone, l.localeName),
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
                        Text(l.isolate(holder), style: strong),
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
          // When the gate side is the taller face, the extra room goes above the perforation.
          const Spacer(),
          const Perforation(),
          Padding(
            padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.xs, JomhorSpace.xl, JomhorSpace.xl),
            child: switch ((entry, t.hasQr)) {
              (final entry?, _) => _Entered(ticket: t, entry: entry),
              (null, true) => RotatingQr(ticket: t, now: now),
              (null, false) => Padding(
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
            },
          ),
        ],
      ),
    );
  }
}

/// "Tu es dedans" / "راك داخل" and "Entré à 18:42 · Porte 4" (venue time), where the QR was.
class _Entered extends StatelessWidget {
  const new({required this.ticket, required this.entry});

  final FanTicket ticket;
  final Entry entry;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final e = ticket.event;
    return Padding(
      key: const Key('ticket.entered'),
      padding: const EdgeInsets.symmetric(vertical: JomhorSpace.xl),
      child: Column(
        children: [
          const SizedBox.square(
            dimension: 88,
            child: DecoratedBox(
              decoration: BoxDecoration(color: JomhorColors.success, shape: BoxShape.circle),
              child: Icon(Icons.check_rounded, size: 52, color: JomhorColors.text),
            ),
          ),
          const SizedBox(height: JomhorSpace.l),
          Text(
            isRtl(context) ? l.ticketEntered : l.ticketEntered.toUpperCase(),
            textAlign: TextAlign.center,
            style: JomhorType.hero.copyWith(color: JomhorColors.ticketInk, fontSize: 40),
          ),
          const SizedBox(height: JomhorSpace.s),
          Text(
            l.ticketEnteredAt(l.ltr(venueClock(entry.at, e.timezone, l.localeName)), l.ltr(entry.gateCode)),
            textAlign: TextAlign.center,
            style: JomhorType.body.copyWith(color: JomhorColors.ticketMuted, fontSize: 14),
          ),
        ],
      ),
    );
  }
}

/// Two ground-coloured notches and a dashed rule between the facts and the QR.
class Perforation extends StatelessWidget {
  const new({super.key});

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
        // Sized here: the card measures its faces (IntrinsicHeight), which the QR's own layout cannot answer.
        SizedBox.square(
          dimension: size,
          child: RepaintBoundary(
            child: TicketQr(data: ticket.rotatingToken(now)!, size: size, label: l.ticketQrLabel(ticket.ticketNo)),
          ),
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

/// Screen 5 actions (DESIGN.md Lot 4): Transférer (screen 8) and the WhatsApp re-send of the PDF, both for a VALID
/// ticket. The re-send call runs only on a tap. Transférer is off after `transfer_until`, without a cutoff, or once
/// the server said the ticket was admitted (a refusal, or `/load` entries), with the reason under it.
class _Actions extends ConsumerStatefulWidget {
  const new({required this.ticket, required this.now, required this.entered, required this.onTransfer});

  final FanTicket ticket;

  /// `/load` listed it: admitted at a gate, so it can no longer be transferred.
  final bool entered;

  /// Server time of this frame: the action turns off the second `transfer_until` passes.
  final DateTime now;

  /// Null while the ticket slides out.
  final VoidCallback? onTransfer;

  @override
  ConsumerState<_Actions> createState() => _ActionsState();
}

class _ActionsState extends ConsumerState<_Actions> {
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
    final t = widget.ticket;
    final valid = t.status == 'VALID';
    final refusals = ref.watch(transferRefusalsProvider);
    final block = widget.entered ? TransferBlock.admitted : blockOf(t, widget.now, refusals);
    final blocked = transferBlockText(l, t, block, refusals[t.id]);
    Widget action(Widget button) => Expanded(
      child: ConstrainedBox(constraints: const BoxConstraints(minHeight: 56), child: button),
    );
    return Padding(
      padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.m, JomhorSpace.xl, JomhorSpace.l),
      child: Column(
        children: [
          Row(
            children: [
              action(
                OutlinedButton.icon(
                  key: const Key('ticket.transfer'),
                  onPressed: block == null ? widget.onTransfer : null,
                  icon: const Icon(Icons.swap_horiz_rounded, size: 20),
                  label: Text(l.transferAction),
                ),
              ),
              const SizedBox(width: JomhorSpace.m),
              action(
                OutlinedButton.icon(
                  key: const Key('ticket.whatsapp'),
                  onPressed: valid && !_sending ? _send : null,
                  icon: _sending
                      ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.send_rounded, size: 20),
                  label: Text(l.ticketWhatsApp),
                ),
              ),
            ],
          ),
          const SizedBox(height: JomhorSpace.s),
          Text(
            blocked ?? l.ticketWhatsAppHint,
            key: blocked == null ? null : const Key('ticket.transferBlocked'),
            textAlign: TextAlign.center,
            style: JomhorType.caption.copyWith(color: JomhorColors.textMuted, fontSize: 12),
          ),
        ],
      ),
    );
  }
}

/// The card as a physical object (DESIGN.md principle 6, `motion.ticketFlip`): every face is laid out, the card is
/// as tall as the tallest (its parent gives it that height), and a change of [shown] turns it about its vertical
/// axis: the old face to 90°, then the new one from -90°. Instant under reduced motion. Hidden faces are neither
/// painted, tapped nor read out.
class _Flip extends StatefulWidget {
  const new({required this.shown, required this.faces});

  final String shown;
  final Map<String, Widget> faces;

  @override
  State<_Flip> createState() => _FlipState();
}

class _FlipState extends State<_Flip> with SingleTickerProviderStateMixin {
  late final _turn = AnimationController(vsync: this, value: 1);
  String? _from;

  @override
  void didUpdateWidget(_Flip old) {
    super.didUpdateWidget(old);
    if (old.shown == widget.shown) return;
    _from = old.shown;
    _turn.duration = motionOf(context, JomhorMotion.ticketFlip);
    if (_turn.duration == Duration.zero) {
      _turn.value = 1;
    } else {
      _turn.forward(from: 0);
    }
  }

  @override
  void dispose() {
    _turn.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Turns the way the page reads: towards the start edge.
    final sign = isRtl(context) ? -1 : 1;
    return AnimatedBuilder(
      animation: _turn,
      builder: (context, _) {
        final t = Curves.easeInOutCubic.transform(_turn.value);
        return Stack(
          fit: StackFit.expand,
          children: [
            for (final MapEntry(key: name, value: face) in widget.faces.entries)
              () {
                final angle = switch (name) {
                  _ when name == widget.shown && t >= 0.5 => -(1 - t) * pi,
                  _ when name == _from && t < 0.5 => t * pi,
                  _ => null,
                };
                return Visibility(
                  key: Key('ticket.face.$name'),
                  visible: angle != null,
                  maintainSize: true,
                  maintainAnimation: true,
                  maintainState: true,
                  child: Transform(
                    alignment: Alignment.center,
                    transform: Matrix4.identity()
                      ..setEntry(3, 2, 0.0012)
                      ..rotateY(sign * (angle ?? 0)),
                    child: face,
                  ),
                );
              }(),
          ],
        );
      },
    );
  }
}

/// [child] faded out and out of reach (taps, screen readers) while not [shown]; `motion.base`, instant under reduced
/// motion.
class _Fade extends StatelessWidget {
  const new({required this.shown, required this.child, super.key});

  final bool shown;
  final Widget child;

  @override
  Widget build(BuildContext context) => IgnorePointer(
    ignoring: !shown,
    child: ExcludeSemantics(
      excluding: !shown,
      child: AnimatedOpacity(
        opacity: shown ? 1 : 0,
        duration: motionOf(context, JomhorMotion.base),
        curve: Curves.easeOut,
        child: child,
      ),
    ),
  );
}
