import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/queue/queue.dart';
import 'package:jomhor_fan/features/queue/queue_controller.dart';
import 'package:jomhor_fan/features/queue/queue_store.dart';
import 'package:jomhor_fan/features/queue/room_cta.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

/// Keeps the screen on (or lets it sleep). Tests override it to watch the calls.
final wakelockProvider = Provider<Future<void> Function({required bool on})>(
  (ref) => ({required on}) async {
    try {
      await WakelockPlus.toggle(enable: on);
    } on Object {
      // A device without the control: the screen may sleep, the place is kept anyway.
    }
  },
);

/// Screen 12 · File d'attente (DESIGN.md Lot 5): the pre-queue and its draw, the position walking down the twelve
/// dots, the turn, and the refusals. Kept awake while visible; the place is kept when the fan leaves.
class QueueScreen extends ConsumerStatefulWidget {
  const new({required this.eventId, super.key});

  final int eventId;

  @override
  ConsumerState<QueueScreen> createState() => _QueueScreenState();
}

class _QueueScreenState extends ConsumerState<QueueScreen> with SingleTickerProviderStateMixin {
  late final _swell = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: JomhorMotion.ticketFlip),
  );
  late final AppLifecycleListener _lifecycle;
  late final Timer _tick;
  late final Future<void> Function({required bool on}) _wakelock;

  QueueController get _controller => ref.read(queueControllerProvider(widget.eventId).notifier);

  @override
  void initState() {
    super.initState();
    _wakelock = ref.read(wakelockProvider);
    unawaited(_wakelock(on: true));
    _lifecycle = AppLifecycleListener(
      onStateChange: (s) {
        switch (s) {
          case AppLifecycleState.resumed:
            unawaited(_wakelock(on: true));
            _controller.resume();
          case AppLifecycleState.hidden || AppLifecycleState.paused:
            _controller.pause();
            unawaited(_wakelock(on: false));
          case AppLifecycleState.inactive || AppLifecycleState.detached:
            break;
        }
      },
    );
    // Countdowns (the draw, the turn) redraw each second.
    _tick = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _tick.cancel();
    _lifecycle.dispose();
    _swell.dispose();
    unawaited(_wakelock(on: false));
    super.dispose();
  }

  /// "Ta place est gardée" when leaving with a place, then back.
  void _back(QueueView v) {
    final phase = v.status?.phase;
    if (phase != null && (phase.polls || phase == QueuePhase.admitted)) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(AppLocalizations.of(context).queuePlaceKept)));
    }
    context.canPop() ? context.pop() : context.go('/events/${widget.eventId}');
  }

  void _onChange(QueueView? before, QueueView now) {
    // No room any more: buy without it.
    final ended =
        now.status?.phase == QueuePhase.ended ||
        (now.error?.code == 'QUEUE_NOT_OPEN' && const {'ENDED', 'NO_ROOM'}.contains(now.error?.details['reason']));
    if (ended) return context.go('/events/${widget.eventId}/zones');
    // The turn: the amber twelfth swells once, with a haptic (neither under reduced motion).
    if (now.status?.phase == QueuePhase.admitted && before?.status?.phase != QueuePhase.admitted) {
      if (reduceMotion(context)) {
        _swell.value = 1;
      } else {
        _swell.forward(from: 0);
        unawaited(HapticFeedback.mediumImpact());
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final id = widget.eventId;
    final v = ref.watch(queueControllerProvider(id));
    ref.listen(queueControllerProvider(id), _onChange);
    final detail = ref.watch(eventDetailProvider(id)).value;
    final now = ref.read(nowProvider)();

    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsetsDirectional.fromSTEB(JomhorSpace.s, JomhorSpace.s, JomhorSpace.xl, 0),
              child: Row(
                children: [
                  BackButton(onPressed: () => _back(v)),
                  if (detail != null) Expanded(child: _EventLine(event: detail.event)),
                ],
              ),
            ),
            if (v.offline && v.status != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.s, JomhorSpace.xl, 0),
                child: InfoBanner(key: const Key('queue.offline'), text: l.queueOffline, icon: Icons.wifi_off_rounded),
              ),
            Expanded(
              child: Semantics(
                container: true,
                label: l.queueTitle,
                // The state centred, its info rows at the bottom (each list carries its spacers); scrolls with
                // large text.
                child: CustomScrollView(
                  slivers: [
                    SliverPadding(
                      padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.l, JomhorSpace.xl, JomhorSpace.l),
                      sliver: SliverFillRemaining(
                        hasScrollBody: false,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: _content(context, l, v, detail, now),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            ?_action(context, l, v, detail, now),
          ],
        ),
      ),
    );
  }

  List<Widget> _content(BuildContext context, AppLocalizations l, QueueView v, EventDetail? detail, DateTime now) {
    final status = v.status;
    final event = detail?.event;
    final zone = event?.timezone ?? 'UTC';
    final serverNow = v.serverNow(now);
    if (v.error case final e?) return [const Spacer(), ..._refusal(context, l, v, e, zone, event, now), const Spacer()];
    if (status == null || status.phase == QueuePhase.ended) {
      return [
        const Spacer(),
        const QueueDots(),
        const SizedBox(height: JomhorSpace.xl),
        Text(l.queueJoining, textAlign: TextAlign.center, style: JomhorType.body),
        const Spacer(),
      ];
    }
    final left = status.admissionExpiresAt?.difference(serverNow);
    switch (status.phase) {
      case QueuePhase.preQueue:
        final draw = l.ltr(venueClock(status.saleStartsAt, zone, l.localeName));
        final t = status.saleStartsAt.difference(serverNow);
        final s = t.isNegative ? 0 : t.inSeconds;
        return [
          const Spacer(),
          Text(
            l.queueDrawAt(draw).toUpperCase(),
            key: const Key('queue.draw'),
            textAlign: TextAlign.center,
            style: JomhorType.label.copyWith(color: JomhorColors.accent).tracked(context),
          ),
          const SizedBox(height: JomhorSpace.l),
          Row(
            key: const Key('queue.countdown'),
            children: [
              for (final (i, (value, label)) in [
                (s ~/ 3600, l.countdownHours),
                (s ~/ 60 % 60, l.countdownMinutes),
                (s % 60, l.queueSeconds),
              ].indexed) ...[
                if (i > 0) const SizedBox(width: JomhorSpace.s),
                Expanded(
                  child: CountdownTile(value: value, label: label),
                ),
              ],
            ],
          ),
          const SizedBox(height: JomhorSpace.xxl),
          const QueueDots(),
          const SizedBox(height: JomhorSpace.xxl),
          Text(
            l.queueDrawText(draw),
            textAlign: TextAlign.center,
            style: JomhorType.body.copyWith(color: JomhorColors.textSecondary),
          ),
          const SizedBox(height: JomhorSpace.l),
          _InRoom(n: status.inRoom),
          const Spacer(),
          const SizedBox(height: JomhorSpace.xxl),
          ..._infoRows(l, event, status, remaining: false),
        ];
      case QueuePhase.waiting || QueuePhase.paused when status.position == null:
        return [
          const Spacer(),
          const QueueDots(),
          const SizedBox(height: JomhorSpace.xxl),
          Text(l.queueRankingTitle, textAlign: TextAlign.center, style: JomhorType.h1),
          const SizedBox(height: JomhorSpace.s),
          Text(
            l.queueRanking,
            textAlign: TextAlign.center,
            style: JomhorType.body.copyWith(color: JomhorColors.textSecondary),
          ),
          const SizedBox(height: JomhorSpace.l),
          _InRoom(n: status.inRoom),
          const Spacer(),
        ];
      case QueuePhase.waiting || QueuePhase.paused:
        final position = status.position!;
        final wait = waitCopy(l, status.estimatedWaitS);
        return [
          const Spacer(),
          Text(
            l.queuePositionLabel.toUpperCase(),
            textAlign: TextAlign.center,
            style: JomhorType.label.copyWith(color: JomhorColors.textMuted).tracked(context),
          ),
          const SizedBox(height: JomhorSpace.s),
          _Glow(
            size: 280,
            opacity: 0.16,
            child: FittedBox(
              fit: BoxFit.scaleDown,
              child: Roll(
                value: position,
                child: Text(
                  l.queuePosition(position),
                  key: const Key('queue.position'),
                  textScaler: TextScaler.noScaling,
                  style: JomhorType.number.copyWith(fontSize: 88, fontFeatures: const [FontFeature.tabularFigures()]),
                ),
              ),
            ),
          ),
          const SizedBox(height: JomhorSpace.m),
          Text(
            l.queueAhead(position - 1),
            key: const Key('queue.ahead'),
            textAlign: TextAlign.center,
            style: JomhorType.h2,
          ),
          const SizedBox(height: JomhorSpace.s),
          if (status.phase == QueuePhase.paused || wait != null)
            Text(
              status.phase == QueuePhase.paused ? l.queuePaused : l.queueWait(wait!),
              key: const Key('queue.wait'),
              textAlign: TextAlign.center,
              style: JomhorType.body.copyWith(
                color: status.phase == QueuePhase.paused ? JomhorColors.accentHover : JomhorColors.textSecondary,
              ),
            ),
          const SizedBox(height: JomhorSpace.xxl),
          QueueDots(
            lit: litDots(position: position, first: v.firstPosition ?? position),
          ),
          if (event?.room?.eligibility != null) ...[
            const SizedBox(height: JomhorSpace.xl),
            Center(
              child: InfoChip(l.queueVerified, key: const Key('queue.verified'), icon: Icons.verified_rounded),
            ),
          ],
          const Spacer(),
          const SizedBox(height: JomhorSpace.xxl),
          ..._infoRows(l, event, status, remaining: true, zones: detail?.zones),
        ];
      case QueuePhase.admitted when left != null && left > Duration.zero:
        return [
          const Spacer(),
          QueueDots(lit: 12, swell: _swell),
          const SizedBox(height: JomhorSpace.xxl * 2),
          _Glow(
            size: 320,
            opacity: 0.2,
            child: Text(
              l.queueTurnTitle.toUpperCase(),
              key: const Key('queue.turn'),
              textAlign: TextAlign.center,
              style: JomhorType.hero.copyWith(fontSize: 56, color: JomhorColors.accent),
            ),
          ),
          const SizedBox(height: JomhorSpace.m),
          Text(
            l.queueTurnLeft(l.ltr(mmss(left))),
            key: const Key('queue.turnLeft'),
            textAlign: TextAlign.center,
            style: JomhorType.h2,
          ),
          const Spacer(),
          const SizedBox(height: JomhorSpace.xxl),
          ..._infoRows(l, event, status, remaining: true, zones: detail?.zones, keep: false),
        ];
      case QueuePhase.admitted || QueuePhase.expired || QueuePhase.ended:
        return [
          const Spacer(),
          const QueueDots(lit: 0),
          const SizedBox(height: JomhorSpace.xxl),
          Text(l.queueExpiredTitle, key: const Key('queue.expired'), textAlign: TextAlign.center, style: JomhorType.h1),
          const SizedBox(height: JomhorSpace.s),
          Text(
            l.queueExpiredBody,
            textAlign: TextAlign.center,
            style: JomhorType.body.copyWith(color: JomhorColors.textSecondary),
          ),
          const Spacer(),
        ];
    }
  }

  List<Widget> _infoRows(
    AppLocalizations l,
    FanEvent? event,
    QueueStatus status, {
    required bool remaining,
    List<Zone>? zones,
    bool keep = true,
  }) {
    final left = zones?.fold(0, (n, z) => n + z.remaining);
    return [
      if (keep) _InfoRow(icon: Icons.bookmark_added_outlined, text: l.queueKeepPlace),
      if (event != null) _InfoRow(icon: Icons.confirmation_number_outlined, text: l.queueLimit(event.perAccountLimit)),
      if (remaining && status.soldOut)
        _InfoRow(key: const Key('queue.soldOut'), icon: Icons.event_seat_outlined, text: l.queueSoldOut)
      else if (remaining && left != null)
        _InfoRow(key: const Key('queue.remaining'), icon: Icons.event_seat_outlined, text: l.queueRemaining(left)),
    ];
  }

  List<Widget> _refusal(
    BuildContext context,
    AppLocalizations l,
    QueueView v,
    ApiError e,
    String zone,
    FanEvent? event,
    DateTime now,
  ) {
    String day(Object? at) => at is String ? venueDay(DateTime.parse(at), zone, l.localeName) : '';
    return switch ((e.code, e.details['reason'])) {
      ('NOT_ELIGIBLE', final reason) => [
        _Panel(
          key: const Key('queue.notEligible'),
          icon: Icons.verified_user_outlined,
          title: l.queueNotEligibleTitle,
          body: switch ((reason, e.details)) {
            ('ACCOUNT_TOO_NEW', {'verified_before': final d}) => l.queueAccountTooNew(day(d)),
            ('NOT_MEMBER', {'member_joined_before': final d}) => l.queueNotMember(day(d)),
            ('NOT_ENOUGH_EVENTS', {'min_events_attended': final int n, 'attended': final int a}) =>
              l.queueNotEnoughEvents(n, a),
            _ => null,
          },
        ),
      ],
      ('QUEUE_DEVICE_LIMIT', _) => [
        _Panel(key: const Key('queue.deviceLimit'), icon: Icons.phone_android_rounded, title: l.queueDeviceLimit),
      ],
      ('QUEUE_RATE_LIMITED', _) => [
        const QueueDots(),
        const SizedBox(height: JomhorSpace.xxl),
        Text(
          v.retryIn == null ? l.queueRateLimitedAgain : l.queueRateLimited(v.retryIn!),
          key: const Key('queue.rateLimited'),
          textAlign: TextAlign.center,
          style: JomhorType.body.copyWith(color: JomhorColors.textSecondary),
        ),
      ],
      ('QUEUE_NOT_OPEN', 'NOT_STARTED') => [
        const QueueDots(),
        const SizedBox(height: JomhorSpace.xxl),
        Text(l.queueNotOpenTitle, textAlign: TextAlign.center, style: JomhorType.h1),
        const SizedBox(height: JomhorSpace.s),
        Text(
          l.queueNotOpen,
          textAlign: TextAlign.center,
          style: JomhorType.body.copyWith(color: JomhorColors.textSecondary),
        ),
      ],
      ('QUEUE_NOT_OPEN', 'CLOSED') => [
        _Panel(key: const Key('queue.closed'), icon: Icons.event_busy_outlined, title: l.queueClosed),
      ],
      ('QUEUE_NOT_OPEN', 'ENDED' || 'NO_ROOM') => const [],
      _ => [ErrorPanel(error: e, onRetry: () => unawaited(_controller.retry()))],
    };
  }

  /// The sticky CTA: the turn, a new place, the manual retry, or the disabled opening time.
  Widget? _action(BuildContext context, AppLocalizations l, QueueView v, EventDetail? detail, DateTime now) {
    final status = v.status;
    final e = v.error;
    final left = status?.admissionExpiresAt?.difference(v.serverNow(now));
    final cta = switch ((e?.code, e?.details['reason'])) {
      ('QUEUE_NOT_OPEN', 'NOT_STARTED') => (
        l.queueOpensAt(switch (e!.details['opens_at']) {
          final String at => opensAtText(l, DateTime.parse(at), detail?.event.timezone ?? 'UTC', now),
          _ => '',
        }),
        null,
        const Key('queue.opensAt'),
      ),
      ('QUEUE_RATE_LIMITED', _) when v.retryIn == null => (
        l.retry,
        () => unawaited(_controller.retry()),
        const Key('queue.retry'),
      ),
      (null, _) when status?.phase == QueuePhase.admitted && left != null && left > Duration.zero => (
        l.queueChoose,
        () => context.go('/events/${widget.eventId}/zones'),
        const Key('queue.choose'),
      ),
      (null, _) when status?.phase == QueuePhase.expired || status?.phase == QueuePhase.admitted => (
        l.queueRejoin,
        () => unawaited(_controller.rejoin()),
        const Key('queue.rejoin'),
      ),
      _ => null,
    };
    if (cta == null) return null;
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: JomhorColors.surface,
        border: Border(top: BorderSide(color: JomhorColors.border)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(JomhorSpace.l),
        child: FilledButton(
          key: cta.$3,
          onPressed: cta.$2,
          child: Text(cta.$1, textAlign: TextAlign.center),
        ),
      ),
    );
  }
}

/// Crest 20, the title, the date: which sale this line is for.
class _EventLine extends StatelessWidget {
  const new({required this.event});

  final FanEvent event;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Row(
      children: [
        Crest(name: event.organizer.name, url: event.organizer.crestUrl, size: 20),
        const SizedBox(width: JomhorSpace.s),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                event.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: JomhorType.bodySmall.copyWith(fontWeight: FontWeight.w600, color: JomhorColors.text),
              ),
              Text(
                l.dotPair(
                  venueDate(event.startsAt, event.timezone, l.localeName),
                  venueClock(event.startsAt, event.timezone, l.localeName),
                ),
                style: JomhorType.caption.copyWith(color: JomhorColors.textMuted, fontSize: 12),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// The soft accent light behind [child], drawn past its box without taking layout room.
class _Glow extends StatelessWidget {
  const new({required this.size, required this.opacity, required this.child});

  final double size;
  final double opacity;
  final Widget child;

  @override
  Widget build(BuildContext context) => Stack(
    alignment: Alignment.center,
    clipBehavior: Clip.none,
    children: [
      Positioned.fill(
        child: OverflowBox(
          minWidth: 0,
          minHeight: 0,
          maxWidth: size,
          maxHeight: size,
          child: AccentGlow(size: size, opacity: opacity),
        ),
      ),
      child,
    ],
  );
}

/// "{n} fans dans la file", rolling when it changes.
class _InRoom extends StatelessWidget {
  const new({required this.n});

  final int n;

  @override
  Widget build(BuildContext context) => Roll(
    value: n,
    child: Text(
      AppLocalizations.of(context).queueInRoom(n),
      key: const Key('queue.inRoom'),
      textAlign: TextAlign.center,
      style: JomhorType.body.copyWith(fontWeight: FontWeight.w600),
    ),
  );
}

class _InfoRow extends StatelessWidget {
  const new({required this.icon, required this.text, super.key});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: JomhorSpace.m),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 18, color: JomhorColors.textMuted),
        const SizedBox(width: JomhorSpace.m),
        Expanded(
          child: Text(text, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary)),
        ),
      ],
    ),
  );
}

/// An outlined panel: a refusal the fan cannot wait out (not eligible, too many accounts, sales closed).
class _Panel extends StatelessWidget {
  const new({required this.icon, required this.title, this.body, super.key});

  final IconData icon;
  final String title;
  final String? body;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(JomhorSpace.xl),
    decoration: BoxDecoration(
      border: Border.all(color: JomhorColors.borderMuted),
      borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.card)),
    ),
    child: Column(
      children: [
        Icon(icon, size: 32, color: JomhorColors.accent),
        const SizedBox(height: JomhorSpace.m),
        Text(title, textAlign: TextAlign.center, style: JomhorType.h2),
        if (body case final body?) ...[
          const SizedBox(height: JomhorSpace.s),
          Text(
            body,
            textAlign: TextAlign.center,
            style: JomhorType.body.copyWith(color: JomhorColors.textSecondary),
          ),
        ],
      ],
    ),
  );
}

/// The twelve dots of screen 12 (DESIGN.md principle 6, Lot 5): a straight row in reading direction, never the J
/// mark. [lit] null = indeterminate, a light running along the row (still under reduced motion); else the first
/// [lit] dots are white. The twelfth is amber: outlined until the turn, then filled, swelling once with [swell].
class QueueDots extends StatefulWidget {
  const new({this.lit, this.swell, super.key});

  final int? lit;
  final Animation<double>? swell;

  static const dot = 12.0;
  static const twelfth = 18.0;

  @override
  State<QueueDots> createState() => _QueueDotsState();
}

class _QueueDotsState extends State<QueueDots> with SingleTickerProviderStateMixin {
  AnimationController? _run;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _sync();
  }

  @override
  void didUpdateWidget(QueueDots old) {
    super.didUpdateWidget(old);
    _sync();
  }

  void _sync() {
    if (widget.lit == null && !reduceMotion(context)) {
      _run ??= AnimationController(vsync: this, duration: const Duration(milliseconds: 1600))..repeat();
    } else {
      _run?.dispose();
      _run = null;
    }
  }

  @override
  void dispose() {
    _run?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final lit = widget.lit;
    return Semantics(
      key: const Key('queue.dots'),
      label: l.queueProgress,
      value: lit == null ? null : '$lit / 12',
      excludeSemantics: true,
      child: AnimatedBuilder(
        animation: Listenable.merge([_run, widget.swell]),
        builder: (context, _) {
          // The light's centre walks from before the first dot to past the twelfth.
          final light = _run == null ? null : _run!.value * 15 - 1.5;
          return Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              for (var i = 0; i < 12; i++)
                if (i < 11)
                  _dot(
                    QueueDots.dot,
                    Color.lerp(
                      JomhorColors.border,
                      JomhorColors.text,
                      lit != null ? (i < lit ? 1 : 0) : (light == null ? 0 : math.max(0, 1 - (light - i).abs() / 1.6)),
                    )!,
                    key: Key('queue.dot.$i.${lit != null && i < lit ? 'lit' : 'off'}'),
                  )
                else
                  _twelfth(on: lit == 12, glow: light == null ? 0 : math.max(0, 1 - (light - i).abs() / 1.6)),
            ],
          );
        },
      ),
    );
  }

  Widget _dot(double size, Color color, {Key? key}) => Container(
    key: key,
    width: size,
    height: size,
    decoration: BoxDecoration(shape: BoxShape.circle, color: color),
  );

  Widget _twelfth({required bool on, required double glow}) {
    final t = widget.swell?.value ?? 1;
    // One swell: up to 1.5x by mid-way, back to rest (DESIGN.md: ≤ 420 ms).
    final scale = on && t < 1 ? 1 + 0.5 * math.sin(math.pi * t) : 1.0;
    return SizedBox.square(
      dimension: QueueDots.twelfth,
      child: Transform.scale(
        scale: scale,
        child: Container(
          key: Key('queue.dot.11.${on ? 'lit' : 'off'}'),
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: on ? JomhorColors.accent : JomhorColors.accent.withValues(alpha: 0.15 + 0.5 * glow),
            border: Border.all(color: JomhorColors.accent, width: 2),
            boxShadow: on ? [BoxShadow(color: JomhorColors.accent.withValues(alpha: 0.45), blurRadius: 16)] : null,
          ),
        ),
      ),
    );
  }
}

/// Screens 3 and 4 while the turn runs: a slim "Ton tour · mm:ss" bar above the content (DESIGN.md Lot 5).
class AdmissionBar extends ConsumerStatefulWidget {
  const new({required this.eventId, super.key});

  final int eventId;

  @override
  ConsumerState<AdmissionBar> createState() => _AdmissionBarState();
}

class _AdmissionBarState extends ConsumerState<AdmissionBar> {
  late final Timer _tick;

  @override
  void initState() {
    super.initState();
    _tick = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _tick.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final pass = ref.watch(queuePassesProvider).value?[widget.eventId];
    final now = ref.read(nowProvider)();
    if (pass == null || !pass.admitted(now)) return const SizedBox.shrink();
    final l = AppLocalizations.of(context);
    return Container(
      key: const Key('admission.bar'),
      padding: const EdgeInsets.symmetric(vertical: JomhorSpace.s, horizontal: JomhorSpace.xl),
      decoration: BoxDecoration(
        color: JomhorColors.accent.withValues(alpha: 0.12),
        border: Border(bottom: BorderSide(color: JomhorColors.accent.withValues(alpha: 0.5))),
      ),
      child: Row(
        children: [
          const Icon(Icons.timer_outlined, size: 16, color: JomhorColors.accent),
          const SizedBox(width: JomhorSpace.s),
          Text(
            l.turnBar(l.ltr(mmss(pass.admissionUntil!.difference(now)))),
            style: JomhorType.bodySmall.copyWith(color: JomhorColors.accentHover, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}
