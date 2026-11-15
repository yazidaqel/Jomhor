import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/auth/auth_repository.dart' show FanUser;
import 'package:jomhor_fan/features/checkout/checkout_controller.dart' show meProvider;
import 'package:jomhor_fan/features/passes/buy_controller.dart';
import 'package:jomhor_fan/features/passes/pass.dart';
import 'package:jomhor_fan/features/passes/passes_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Screen 13 · Abonnement, buy (DESIGN.md Lot 5 step 3): the club and the product, the season in one card (zone,
/// venue, the events announced), the holder's name, and "Acheter · {amount}" → `POST /fan/product-orders` → screen 4
/// product variant (`/orders/{id}`). Public to read; buying asks for the login first.
class BuyScreen extends ConsumerWidget {
  const new({required this.productId, super.key});

  final String productId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final product = ref.watch(productProvider(productId));
    return Scaffold(
      // A membership is not an "Abonnement" (FL-30 sells it from Club, but its link opens here too).
      appBar: AppBar(title: Text(product.value?.isPass == false ? l.clubMemberCard : l.passChip)),
      body: switch (product) {
        AsyncValue(value: final p?) => _Buy(product: p),
        AsyncError(:final error) => Center(
          child: ErrorPanel(error: error, onRetry: () => ref.invalidate(productProvider(productId))),
        ),
        _ => ListView(
          physics: const NeverScrollableScrollPhysics(),
          padding: const EdgeInsets.all(JomhorSpace.xl),
          children: const [SkeletonList(rows: 4)],
        ),
      },
    );
  }
}

class _Buy extends ConsumerStatefulWidget {
  const new({required this.product});

  final Product product;

  @override
  ConsumerState<_Buy> createState() => _BuyState();
}

class _BuyState extends ConsumerState<_Buy> {
  final _name = TextEditingController();

  /// `/fan/me`, listened to once a session exists (the login pops back to this screen).
  ProviderSubscription<AsyncValue<FanUser>>? _me;

  @override
  void initState() {
    super.initState();
    // Prefilled once from /fan/me (§Holder names): the pass is held by its buyer.
    ref.listenManual(sessionProvider, (_, signedIn) {
      if (!signedIn || _me != null) return;
      _me = ref.listenManual(meProvider, (_, me) {
        final name = me.value?.displayName;
        if (name != null && _name.text.isEmpty) _name.text = name;
      }, fireImmediately: true);
    }, fireImmediately: true);
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _buy() async {
    final id = widget.product.id;
    if (!ref.read(sessionProvider)) {
      // Pushed without `from`: the login pops back to this screen once signed in.
      unawaited(context.push('/login'));
      return;
    }
    final next = await ref.read(buyProvider(id).notifier).buy(_name.text);
    if (next != null && mounted) unawaited(context.push(next));
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final p = widget.product;
    final state = ref.watch(buyProvider(p.id));
    final soldOut = p.soldOut || state.soldOut;
    final off = soldOut || state.closed;
    final club = p.club;
    final pass = p.pass;
    final error = state.error;

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.l, JomhorSpace.xl, JomhorSpace.xl),
            children: [
              Entrance(
                child: Row(
                  children: [
                    Crest(key: const Key('buy.crest'), name: club.name, url: club.crestUrl, size: 20),
                    const SizedBox(width: JomhorSpace.s),
                    Expanded(
                      child: Text(
                        p.name,
                        key: const Key('buy.name'),
                        style: JomhorType.body.copyWith(fontWeight: FontWeight.w600, color: JomhorColors.textSecondary),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: JomhorSpace.m),
              Entrance(
                index: 1,
                child: _SeasonCard(product: p, soldOut: soldOut),
              ),
              if (pass != null && pass.events.isNotEmpty) ...[
                const SizedBox(height: JomhorSpace.xl),
                SectionLabel(l.passProgramme),
                const SizedBox(height: JomhorSpace.s),
                for (final (i, e) in pass.events.indexed)
                  Entrance(
                    index: i + 2,
                    child: SeasonEventRow(
                      key: Key('buy.event.${e.id}'),
                      title: e.title,
                      startsAt: e.startsAt,
                      timezone: p.timezone,
                    ),
                  ),
              ],
              const SizedBox(height: JomhorSpace.xl),
              SectionLabel(l.ticketHolder),
              const SizedBox(height: JomhorSpace.s),
              TextField(
                key: const Key('buy.holder'),
                controller: _name,
                enabled: !state.busy && !off,
                textCapitalization: TextCapitalization.words,
                autofillHints: const [AutofillHints.name],
                decoration: InputDecoration(
                  labelText: l.holderNameLabel,
                  helperText: l.passHolderHelp,
                  helperMaxLines: 2,
                  errorText: state.badName ? l.holderNameError : null,
                  errorMaxLines: 3,
                  prefixIcon: const Icon(Icons.badge_outlined),
                ),
              ),
            ],
          ),
        ),
        DecoratedBox(
          decoration: const BoxDecoration(
            color: JomhorColors.surface,
            border: Border(top: BorderSide(color: JomhorColors.border)),
          ),
          child: SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.all(JomhorSpace.l),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (error != null) ...[
                    Text(
                      apiErrorText(l, error),
                      key: const Key('buy.error'),
                      style: JomhorType.bodySmall.copyWith(color: Theme.of(context).colorScheme.error),
                    ),
                    const SizedBox(height: JomhorSpace.s),
                  ],
                  FilledButton(
                    key: const Key('buy.cta'),
                    onPressed: state.busy || off ? null : _buy,
                    child: Text(soldOut ? l.soldOut : l.passBuy(l.dh(p.chargeMad))),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// The season in one card: the price in display type, then zone, venue and what arrives ("{n} matchs annoncés",
/// "Un billet dans l'app pour chaque match publié"). A membership says its validity instead.
class _SeasonCard extends StatelessWidget {
  const new({required this.product, required this.soldOut});

  final Product product;
  final bool soldOut;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final p = product;
    final pass = p.pass;
    Widget fact(IconData icon, String text, {Key? key}) => Padding(
      padding: const EdgeInsets.only(top: JomhorSpace.s),
      child: Row(
        children: [
          Icon(icon, size: 18, color: JomhorColors.accent),
          const SizedBox(width: JomhorSpace.s),
          Expanded(
            child: Text(
              text,
              key: key,
              style: JomhorType.body.copyWith(color: JomhorColors.textSecondary),
            ),
          ),
        ],
      ),
    );
    return Container(
      clipBehavior: Clip.antiAlias,
      decoration: const BoxDecoration(
        color: JomhorColors.surface2,
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.cardLarge)),
      ),
      child: Stack(
        children: [
          const PositionedDirectional(top: -60, end: -60, child: AccentGlow(size: 200)),
          Padding(
            padding: const EdgeInsets.all(JomhorSpace.xl),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  (pass?.series.name ?? l.clubMemberCard).toUpperCase(),
                  style: JomhorType.label.copyWith(color: JomhorColors.textMuted).tracked(context),
                ),
                const SizedBox(height: JomhorSpace.xs),
                Text(
                  l.dh(p.chargeMad),
                  key: const Key('buy.price'),
                  style: JomhorType.hero.copyWith(color: soldOut ? JomhorColors.textMuted : JomhorColors.accent),
                ),
                if (soldOut) ...[
                  const SizedBox(height: JomhorSpace.xs),
                  _MutedPill(text: l.soldOut, key: const Key('buy.soldOut')),
                ],
                const SizedBox(height: JomhorSpace.s),
                if (pass != null) ...[
                  fact(Icons.event_seat_outlined, pass.zone.name, key: const Key('buy.zone')),
                  fact(
                    Icons.stadium_outlined,
                    pass.venueCity == null ? pass.venueName : l.dotPair(pass.venueName, pass.venueCity!),
                    key: const Key('buy.venue'),
                  ),
                  fact(
                    Icons.calendar_month_outlined,
                    pass.matches
                        ? l.passEventsAnnounced(pass.events.length)
                        : l.passEventsAnnouncedAny(pass.events.length),
                    key: const Key('buy.count'),
                  ),
                  fact(Icons.qr_code_2_rounded, pass.matches ? l.passOneTicketEach : l.passOneTicketEachAny),
                ] else if (p.validUntil case final until?)
                  fact(Icons.verified_outlined, l.membershipValidUntil(venueDay(until, p.timezone, l.localeName))),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// A muted pill: "Complet" here, "Bientôt" on screen 13 owned.
class _MutedPill extends StatelessWidget {
  const new({required this.text, super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.s, vertical: 2),
    decoration: const BoxDecoration(
      border: Border.fromBorderSide(BorderSide(color: JomhorColors.borderMuted)),
      borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.pill)),
    ),
    child: Text(
      text,
      style: JomhorType.caption.copyWith(color: JomhorColors.textSecondary, fontWeight: FontWeight.w600, fontSize: 12),
    ),
  );
}

/// "Bientôt": a season event whose ticket isn't issued yet (screen 13 owned).
Widget soonPill(String text, {Key? key}) => _MutedPill(text: text, key: key);

/// One event of the season: date badge, title and start in the venue's zone; [trailing] at the end.
class SeasonEventRow extends StatelessWidget {
  const new({required this.title, required this.startsAt, required this.timezone, this.trailing, super.key});

  final String title;
  final DateTime startsAt;
  final String timezone;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final (day, month) = venueDayMonth(startsAt, timezone, l.localeName);
    return Padding(
      padding: const EdgeInsets.only(bottom: JomhorSpace.s),
      child: Row(
        children: [
          DateBadge(day: day, month: month),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: JomhorType.body.copyWith(fontWeight: FontWeight.w600)),
                Text(
                  l.dotPair(
                    venueDate(startsAt, timezone, l.localeName),
                    l.ltr(venueClock(startsAt, timezone, l.localeName)),
                  ),
                  style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                ),
              ],
            ),
          ),
          if (trailing case final t?) ...[const SizedBox(width: JomhorSpace.s), t],
        ],
      ),
    );
  }
}
