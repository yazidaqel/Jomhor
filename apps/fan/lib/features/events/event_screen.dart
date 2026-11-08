import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/events/sale_copy.dart';
import 'package:jomhor_fan/features/events/zone_row.dart';
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
    final facts = factsLine(l, e.facts);
    final open = e.saleState(now) == SaleState.onSale;

    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: EdgeInsets.zero,
      children: [
        // Header block: the club light in the corner, the title big.
        DecoratedBox(
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
                        BackButton(onPressed: () => _back(context)),
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
        ),
        Padding(
          padding: const EdgeInsets.all(JomhorSpace.xl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              InfoBanner(key: const Key('event.rules'), text: rulesLabel(l, e, now), success: open),
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

class _Footer extends StatelessWidget {
  const new({required this.event, required this.now, required this.l});

  final FanEvent event;
  final DateTime now;
  final AppLocalizations l;

  @override
  Widget build(BuildContext context) {
    final price = event.fromPriceMad;
    final open = event.saleState(now) == SaleState.onSale;
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
                child: FilledButton(
                  key: const Key('event.cta'),
                  onPressed: open ? () => context.push('/events/${event.id}/zones') : null,
                  child: Text(open ? l.chooseZone : ctaLabel(l, event, now), textAlign: TextAlign.center),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
