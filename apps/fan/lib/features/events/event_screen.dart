import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/events/sale_copy.dart';
import 'package:jomhor_fan/features/events/zone_row.dart';
import 'package:jomhor_fan/features/queue/queue_store.dart';
import 'package:jomhor_fan/features/queue/room_cta.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Screen 2 · Match: header, rules banner, zone rows, sticky "Choisir ma zone".
class EventScreen extends ConsumerWidget {
  const new({required this.id, super.key});

  final int id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = ref.watch(eventDetailProvider(id));
    final now = ref.watch(clockProvider).value ?? ref.read(nowProvider)();
    final l = AppLocalizations.of(context);

    return Scaffold(
      body: detail.when(
        loading: () => SafeArea(
          child: ListView(
            physics: const NeverScrollableScrollPhysics(),
            padding: const EdgeInsets.all(JomhorSpace.xl),
            children: const [SkeletonList(hero: true, rows: 4)],
          ),
        ),
        error: (e, _) => SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.all(JomhorSpace.l),
                child: BackButton(onPressed: () => _back(context)),
              ),
              Expanded(
                child: Center(
                  child: ErrorPanel(error: e, onRetry: () => ref.invalidate(eventDetailProvider(id))),
                ),
              ),
            ],
          ),
        ),
        data: (d) => RefreshIndicator(
          onRefresh: () => ref.refresh(eventDetailProvider(id).future),
          child: _Body(detail: d, now: now),
        ),
      ),
      bottomNavigationBar: switch (detail) {
        AsyncData(:final value) => _Footer(event: value.event, now: now, l: l),
        _ => null,
      },
    );
  }
}

/// Back to where the fan came from, or home when the screen was opened directly (deep link).
void _back(BuildContext context) => context.canPop() ? context.pop() : context.go('/');

class _Body extends StatelessWidget {
  const new({required this.detail, required this.now});

  final EventDetail detail;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final e = detail.event;
    final open = e.saleState(now) == SaleState.onSale;

    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: EdgeInsets.zero,
      children: [
        EventHeader(
          event: e,
          leading: BackButton(onPressed: () => _back(context)),
        ),
        Padding(
          padding: const EdgeInsets.all(JomhorSpace.xl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              InfoBanner(key: const Key('event.rules'), text: rulesLabel(l, e, now), success: open),
              // Lot 5: a high-demand sale, and its verified-fan rule when it has one.
              if (e.room != null) ...[
                const SizedBox(height: JomhorSpace.s),
                InfoBanner(
                  key: const Key('event.room'),
                  text: [l.roomHighDemand, ?eligibilityLine(l, e)].join('\n'),
                  accent: true,
                  icon: Icons.groups_rounded,
                ),
              ],
              const SizedBox(height: JomhorSpace.xxl),
              Text(l.zonesTitle, style: JomhorType.h2),
              const SizedBox(height: JomhorSpace.m),
              for (final (i, z) in detail.zones.indexed)
                Entrance(
                  index: i + 1,
                  child: ZoneRow(zone: z, index: i),
                ),
              const SizedBox(height: JomhorSpace.m),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.info_outline_rounded, size: 18, color: JomhorColors.textMuted),
                  const SizedBox(width: JomhorSpace.s),
                  Expanded(
                    child: Text(l.paymentHint, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted)),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// Screen 2's header block (also screen 9 member's): the club line, facts, the title big, date and venue chips, the
/// club light in the corner; [leading] (back) above it.
class EventHeader extends StatelessWidget {
  const new({required this.event, this.leading, super.key});

  final FanEvent event;
  final Widget? leading;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final e = event;
    final facts = factsLine(l, e.facts);
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [JomhorColors.surface2, JomhorColors.surface],
        ),
        border: Border(bottom: BorderSide(color: JomhorColors.border)),
      ),
      child: ClipRect(
        child: Stack(
          children: [
            const PositionedDirectional(bottom: -170, start: -130, child: AccentGlow(size: 380, opacity: 0.2)),
            SafeArea(
              bottom: false,
              child: Padding(
                padding: const EdgeInsetsDirectional.fromSTEB(
                  JomhorSpace.l,
                  JomhorSpace.s,
                  JomhorSpace.xl,
                  JomhorSpace.xxl,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    ?leading,
                    Padding(
                      padding: const EdgeInsetsDirectional.only(start: JomhorSpace.xs, top: JomhorSpace.m),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          ClubLine(name: e.organizer.name, crestUrl: e.organizer.crestUrl),
                          const SizedBox(height: JomhorSpace.m),
                          if (facts != null) ...[
                            Text(
                              facts.toUpperCase(),
                              style: JomhorType.label.copyWith(color: JomhorColors.accent).tracked(context),
                            ),
                            const SizedBox(height: JomhorSpace.s),
                          ],
                          Entrance(child: Text(e.title.toUpperCase(), style: JomhorType.hero)),
                          const SizedBox(height: JomhorSpace.l),
                          Wrap(
                            spacing: JomhorSpace.s,
                            runSpacing: JomhorSpace.s,
                            children: [
                              InfoChip(
                                l.dotPair(
                                  venueDate(e.startsAt, e.timezone, l.localeName),
                                  venueClock(e.startsAt, e.timezone, l.localeName),
                                ),
                                icon: Icons.calendar_today_outlined,
                              ),
                              InfoChip(e.venueName, icon: Icons.place_outlined),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Footer extends ConsumerWidget {
  const new({required this.event, required this.now, required this.l});

  final FanEvent event;
  final DateTime now;
  final AppLocalizations l;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final price = event.fromPriceMad;
    final open = event.saleState(now) == SaleState.onSale;
    // Lot 5: with a room, the CTA as screen 1's (DESIGN.md Lot 5, screen 2).
    final room = roomCta(event, now, ref.watch(queuePassesProvider).value?[event.id]);
    final roomPath = room == null ? null : roomCtaPath(event, room);
    final signedOut = room == RoomCta.opensAt && !ref.watch(sessionProvider);
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: JomhorColors.surface,
        border: Border(top: BorderSide(color: JomhorColors.border)),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.all(JomhorSpace.l),
          child: Row(
            children: [
              if (price != null) ...[
                Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l.footerFrom, style: JomhorType.caption.copyWith(color: JomhorColors.textMuted)),
                    Text(l.dh(price), style: JomhorType.h1),
                  ],
                ),
                const SizedBox(width: JomhorSpace.l),
              ],
              Expanded(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    FilledButton(
                      key: const Key('event.cta'),
                      onPressed: switch (room) {
                        null => open ? () => context.push('/events/${event.id}/zones') : null,
                        _ => roomPath == null ? null : () => context.push(roomPath),
                      },
                      child: Text(switch (room) {
                        null => open ? l.chooseZone : ctaLabel(l, event, now),
                        final r => roomCtaLabel(l, event, r, now),
                      }, textAlign: TextAlign.center),
                    ),
                    if (signedOut)
                      TextButton(
                        key: const Key('event.login'),
                        onPressed: () => context.push(
                          Uri(path: '/login', queryParameters: {'from': '/events/${event.id}'}).toString(),
                        ),
                        child: Text(l.queueLoginNow),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
