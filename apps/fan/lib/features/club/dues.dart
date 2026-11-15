import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/club/club_repository.dart';
import 'package:jomhor_fan/features/club/membership.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/features/passes/buy_controller.dart';
import 'package:jomhor_fan/features/passes/pass.dart';
import 'package:jomhor_fan/features/passes/passes_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// The club's `MEMBERSHIP` product on sale (the first, by name), or null.
Product? duesProduct(WidgetRef ref, String organizerId) =>
    ref.watch(clubProductsProvider(organizerId)).value?.list.where((p) => p.kind == 'MEMBERSHIP').firstOrNull;

/// "Now" for the cotisation: the server time of the club's products read, else the device clock (no read yet).
DateTime duesNow(ClubProducts? products, DateTime Function() deviceNow) => products?.serverAt ?? deviceNow();

/// Whether paying the cotisation would lift [refusal] (`MEMBERS_ONLY`, `NOT_ELIGIBLE` `NOT_MEMBER`): it needs one
/// ([needsDues]) and the fan is a member of [membership]'s club who joined before the cutoff (paying never moves
/// `joined_at`). The link also needs the club followed, so Club can show its card.
bool duesWouldUnlock(ApiError refusal, Membership? membership, {required bool followed}) {
  final joined = membership?.member?.joinedAt;
  if (!followed || joined == null || !needsDues(refusal)) return false;
  return switch (DateTime.tryParse('${refusal.details['member_joined_before']}')) {
    final before? => joined.isBefore(before),
    null => true,
  };
}

/// Screen 7's cotisation, under the member card (DESIGN.md Lot 5 step 3, Q189), once the club sells a membership:
/// unpaid → "Payer ma cotisation"; paid → the accent chip "À jour jusqu'au {date}"; expired → "Renouveler" when a
/// product is on sale. Nothing otherwise. Dates in Morocco's zone, the membership's.
class DuesBlock extends ConsumerWidget {
  const new({required this.club, required this.membership, super.key});

  final Club club;
  final Membership membership;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final product = duesProduct(ref, club.id);
    final dues = duesOf(
      membership,
      onSale: product != null,
      now: duesNow(ref.watch(clubProductsProvider(club.id)).value, ref.watch(nowProvider)),
    );
    return AnimatedSwitcher(
      duration: motionOf(context, JomhorMotion.base),
      child: switch (dues) {
        null => const SizedBox.shrink(),
        Dues.paid => Padding(
          key: Key('club.duesPaid.${club.id}'),
          padding: const EdgeInsets.only(bottom: JomhorSpace.xxl),
          child: Align(
            alignment: AlignmentDirectional.centerStart,
            child: _PaidChip(text: l.clubDuesPaid(venueDay(membership.member!.paidUntil!, homeZone, l.localeName))),
          ),
        ),
        Dues.unpaid || Dues.expired => Padding(
          key: Key('club.dues.${club.id}.${dues.name}'),
          padding: const EdgeInsets.only(bottom: JomhorSpace.xxl),
          child: _DuesCard(dues: dues, product: product),
        ),
      },
    );
  }
}

class _PaidChip extends StatelessWidget {
  const new({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(vertical: JomhorSpace.s, horizontal: JomhorSpace.m),
    decoration: BoxDecoration(
      color: JomhorColors.accent.withValues(alpha: 0.12),
      border: Border.all(color: JomhorColors.accent.withValues(alpha: 0.5)),
      borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.pill)),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Icon(Icons.verified_rounded, size: 18, color: JomhorColors.accent),
        const SizedBox(width: JomhorSpace.s),
        Flexible(
          child: Text(
            text,
            style: JomhorType.bodySmall.copyWith(color: JomhorColors.accentHover, fontWeight: FontWeight.w600),
          ),
        ),
      ],
    ),
  );
}

/// Unpaid ("Cotisation {product} · {price}") or expired, why it matters, and the CTA when a product is on sale.
class _DuesCard extends StatelessWidget {
  const new({required this.dues, required this.product});

  final Dues dues;
  final Product? product;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final p = product;
    final unpaid = dues == Dues.unpaid;
    return Container(
      padding: const EdgeInsets.all(JomhorSpace.l),
      decoration: BoxDecoration(
        color: JomhorColors.surface,
        border: Border.all(color: JomhorColors.accent.withValues(alpha: unpaid ? 0.5 : 0.25)),
        borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.card)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Icon(
                unpaid ? Icons.card_membership_rounded : Icons.event_busy_rounded,
                size: 22,
                color: unpaid ? JomhorColors.accent : JomhorColors.textSecondary,
              ),
              const SizedBox(width: JomhorSpace.m),
              Expanded(
                child: Text(
                  unpaid ? l.clubDuesUnpaid(l.isolate(p!.name), l.dh(p.chargeMad)) : l.clubDuesExpired,
                  style: JomhorType.body.copyWith(fontWeight: FontWeight.w600),
                ),
              ),
            ],
          ),
          const SizedBox(height: JomhorSpace.s),
          Text(l.clubDuesWhy, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary)),
          if (p != null) ...[
            const SizedBox(height: JomhorSpace.l),
            DuesPayButton(key: const Key('club.duesPay'), product: p, label: unpaid ? l.clubDuesPay : l.clubDuesRenew),
          ],
        ],
      ),
    );
  }
}

/// "Voir ma cotisation" under a members-only refusal (screens 3/4 and 12) when paying would lift it
/// ([duesWouldUnlock]): pushes Club on that club's card, so the form or the room is still there on return.
class DuesClubLink extends ConsumerWidget {
  const new({required this.organizerId, required this.refusal, super.key});

  final String organizerId;
  final ApiError refusal;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final followed = ref.watch(followsProvider.select((f) => f.ids.contains(organizerId)));
    final membership = followed && needsDues(refusal) ? ref.watch(membershipProvider(organizerId)).value : null;
    if (!duesWouldUnlock(refusal, membership, followed: followed)) return const SizedBox.shrink();
    return TextButton.icon(
      key: const Key('dues.seeClub'),
      onPressed: () => context.push('/club?organizer=$organizerId', extra: DateTime.now().microsecondsSinceEpoch),
      icon: const Icon(Icons.card_membership_rounded, size: 18),
      label: Text(AppLocalizations.of(context).duesSeeClub),
    );
  }
}

/// Pays [product] in the fan's name (`BuyController.buyAsMe`): screen 4 for the order, or screen 13 to give a name.
/// [pay] replaces that flow (the join sheet joins first and stays locked until it ends). The server's refusal shows
/// under it.
class DuesPayButton extends ConsumerWidget {
  const new({
    required this.product,
    required this.label,
    this.outlined = false,
    this.enabled = true,
    this.pay,
    super.key,
  });

  final Product product;
  final String label;
  final bool outlined;
  final bool enabled;
  final Future<void> Function()? pay;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final state = ref.watch(buyProvider(product.id));
    final off = !enabled || state.busy || state.closed;
    Future<void> buy() async {
      final router = GoRouter.of(context);
      final next = await ref.read(buyProvider(product.id).notifier).buyAsMe();
      if (next == null || !context.mounted) return;
      unawaited(router.push(next));
    }

    final child = state.busy
        ? const SizedBox.square(dimension: 22, child: CircularProgressIndicator(strokeWidth: 2.5))
        : Text(label);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (state.error case final error?) ...[
          Text(
            apiErrorText(l, error),
            key: const Key('club.duesError'),
            style: JomhorType.bodySmall.copyWith(color: JomhorColors.danger),
          ),
          const SizedBox(height: JomhorSpace.s),
        ],
        if (outlined)
          OutlinedButton(onPressed: off ? null : pay ?? buy, child: child)
        else
          FilledButton(onPressed: off ? null : pay ?? buy, child: child),
      ],
    );
  }
}
