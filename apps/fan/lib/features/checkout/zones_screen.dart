import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/checkout/checkout_controller.dart';
import 'package:jomhor_fan/features/checkout/zone_pick.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/events/zone_row.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// Screen 3 · Choix de zone. Lot 1: zone list + stepper (Q22: no stadium map yet).
class ZonesScreen extends ConsumerWidget {
  const new({required this.eventId, super.key});

  final int eventId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final detail = ref.watch(eventDetailProvider(eventId));
    final now = ref.watch(clockProvider).value ?? ref.read(nowProvider)();

    return Scaffold(
      appBar: AppBar(
        backgroundColor: JomhorColors.ground,
        title: Text(l.chooseZone, style: JomhorType.h2),
      ),
      body: detail.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
          child: ErrorPanel(error: e, onRetry: () => ref.invalidate(eventDetailProvider(eventId))),
        ),
        data: (d) => _Body(detail: d, now: now),
      ),
      bottomNavigationBar: switch (detail) {
        AsyncData(:final value) => _Footer(event: value.event, now: now),
        _ => null,
      },
    );
  }
}

class _Body extends ConsumerWidget {
  const new({required this.detail, required this.now});

  final EventDetail detail;
  final DateTime now;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final e = detail.event;
    final pick = ref.watch(zonePickProvider(e.id));
    final picker = ref.read(zonePickProvider(e.id).notifier);
    final limit = e.perAccountLimit;
    final allSoldOut = detail.zones.every((z) => z.soldOut);

    return ListView(
      padding: const EdgeInsets.all(JomhorSpace.xl),
      children: [
        Text(
          l.zonesSubtitle(e.title, venueDate(e.startsAt, e.timezone, l.localeName)),
          style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
        ),
        const SizedBox(height: JomhorSpace.l),
        if (allSoldOut)
          Text(l.allSoldOut, key: const Key('zones.allSoldOut'), style: JomhorType.body)
        else
          Text(l.pickZoneHint, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary)),
        const SizedBox(height: JomhorSpace.m),
        for (final (i, z) in detail.zones.indexed)
          ZoneRow(
            zone: z,
            index: i,
            selected: pick.zone?.id == z.id,
            onTap: () => picker.selectZone(z, perAccountLimit: limit),
          ),
        if (pick case (zone: final zone?, price: final price?, :final qty)) ...[
          const SizedBox(height: JomhorSpace.l),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(JomhorSpace.xl),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(l.pickedZone.toUpperCase(), style: JomhorType.label.copyWith(color: JomhorColors.textMuted)),
                  const SizedBox(height: JomhorSpace.xs),
                  Text(zone.name, style: JomhorType.h2),
                  if (zone.prices.length > 1) ...[
                    const SizedBox(height: JomhorSpace.m),
                    Wrap(
                      spacing: JomhorSpace.s,
                      runSpacing: JomhorSpace.s,
                      children: [
                        for (final p in zone.prices)
                          ChoiceChip(
                            label: Text(l.dotPair(p.name, formatDh(p.priceMad))),
                            selected: p.priceCategoryId == price.priceCategoryId,
                            onSelected: p.remaining == 0 ? null : (_) => picker.selectPrice(p, perAccountLimit: limit),
                          ),
                      ],
                    ),
                  ] else
                    Text(formatDh(price.priceMad), style: JomhorType.body),
                  const SizedBox(height: JomhorSpace.l),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          l.placesMax(maxPlaces(perAccountLimit: limit, remaining: price.remaining)),
                          style: JomhorType.bodySmall,
                        ),
                      ),
                      _StepButton(
                        icon: Icons.remove,
                        tooltip: l.fewerPlaces,
                        onPressed: qty > 1 ? () => picker.step(-1, perAccountLimit: limit) : null,
                      ),
                      SizedBox(
                        width: 48,
                        child: Text(
                          '$qty',
                          key: const Key('zones.qty'),
                          style: JomhorType.h1,
                          textAlign: TextAlign.center,
                        ),
                      ),
                      _StepButton(
                        icon: Icons.add,
                        tooltip: l.morePlaces,
                        onPressed: qty < maxPlaces(perAccountLimit: limit, remaining: price.remaining)
                            ? () => picker.step(1, perAccountLimit: limit)
                            : null,
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: JomhorSpace.m),
          Text(l.groupHint, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted)),
        ],
      ],
    );
  }
}

/// 44 px round stepper button.
class _StepButton extends StatelessWidget {
  const new({required this.icon, required this.tooltip, required this.onPressed});

  final IconData icon;
  final String tooltip;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => IconButton.outlined(
    constraints: const BoxConstraints.tightFor(width: JomhorSize.touchMin, height: JomhorSize.touchMin),
    tooltip: tooltip,
    onPressed: onPressed,
    icon: Icon(icon),
  );
}

class _Footer extends ConsumerWidget {
  const new({required this.event, required this.now});

  final FanEvent event;
  final DateTime now;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final pick = ref.watch(zonePickProvider(event.id));
    final price = pick.price;
    final open = event.saleState(now) == SaleState.onSale;
    final ready = open && price != null && pick.qty > 0;

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
              Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(l.total, style: JomhorType.caption.copyWith(color: JomhorColors.textMuted)),
                  Text(
                    formatDh(price == null ? 0 : price.priceMad * pick.qty),
                    key: const Key('zones.total'),
                    style: JomhorType.h2,
                  ),
                ],
              ),
              const SizedBox(width: JomhorSpace.l),
              Expanded(
                child: FilledButton(
                  key: const Key('zones.continue'),
                  onPressed: ready
                      ? () => context.push(
                          Uri(
                            path: '/events/${event.id}/checkout',
                            queryParameters: {'price': price.priceCategoryId, 'qty': '${pick.qty}'},
                          ).toString(),
                        )
                      : null,
                  child: Text(l.continueCta),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
