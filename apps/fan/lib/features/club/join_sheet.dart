import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/push/push.dart';
import 'package:jomhor_fan/features/club/club_repository.dart';
import 'package:jomhor_fan/features/club/dues.dart';
import 'package:jomhor_fan/features/club/membership.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/passes/buy_controller.dart';
import 'package:jomhor_fan/features/passes/pass.dart';
import 'package:jomhor_fan/features/passes/passes_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/brand.dart';

/// Screen 7 join sheet: two opt-ins, both off by default (lot-4 Q62: marketing needs consent), then
/// `PUT /fan/me/membership`. Resolves with true once joined. A join with push on asks for the push permission, once
/// per install (lot-4 Q126). When the club sells a membership (Lot 5 step 3), "Cotisation {price}" is a second CTA:
/// it joins first with the switches as shown (the same PUT), then orders the membership (screen 4); the sheet stays
/// locked until both end. A rejoin already paid up just closes.
Future<bool?> showJoinSheet(BuildContext context, Club club) => showModalBottomSheet<bool>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  showDragHandle: true,
  backgroundColor: JomhorColors.surface,
  shape: const RoundedRectangleBorder(
    borderRadius: BorderRadius.vertical(top: Radius.circular(JomhorRadius.cardLarge)),
  ),
  builder: (_) => JoinSheet(club: club),
);

class JoinSheet extends ConsumerStatefulWidget {
  const new({required this.club, super.key});

  final Club club;

  @override
  ConsumerState<JoinSheet> createState() => _JoinSheetState();
}

class _JoinSheetState extends ConsumerState<JoinSheet> {
  var _push = false;
  var _whatsapp = false;
  var _sending = false;
  String? _error;

  /// `PUT /fan/me/membership` with the switches; true once joined. On failure the error shows and the sheet stays.
  Future<bool> _save() async {
    final l = AppLocalizations.of(context);
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      await ref.read(membershipProvider(widget.club.id).notifier).save(push: _push, whatsapp: _whatsapp);
      if (_push) unawaited(maybeAskPermission(ref.read(pushMessagingProvider)));
      return true;
    } on Object catch (e) {
      if (mounted) {
        setState(() {
          _sending = false;
          _error = errorText(l, e is ApiError ? e.code : ApiError.unexpectedResponse);
        });
      }
      return false;
    }
  }

  Future<void> _join() async {
    if (await _save() && mounted) Navigator.pop(context, true);
  }

  /// "Cotisation {price}": join with the switches, then order [product] in the fan's name. Locked throughout.
  Future<void> _pay(Product product) async {
    final router = GoRouter.of(context);
    if (!await _save() || !mounted) return;
    final club = widget.club.id;
    // A rejoin keeps `paid_until`: already paid up, nothing to order.
    if (ref.read(membershipProvider(club)).value case final m?
        when duesOf(m, onSale: true, now: duesNow(ref.read(clubProductsProvider(club)).value, ref.read(nowProvider))) ==
            Dues.paid) {
      Navigator.pop(context, true);
      return;
    }
    final next = await ref.read(buyProvider(product.id).notifier).buyAsMe();
    if (!mounted) return;
    if (next == null) {
      // Joined, the order refused (said under the button): the fan may try again.
      setState(() => _sending = false);
      return;
    }
    Navigator.pop(context, true);
    unawaited(router.push(next));
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final paidRequired = ref.watch(membershipProvider(widget.club.id)).value?.paidRequired ?? false;
    final dues = paidRequired ? duesProduct(ref, widget.club.id) : null;
    return SingleChildScrollView(
      padding: EdgeInsets.fromLTRB(
        JomhorSpace.xl,
        0,
        JomhorSpace.xl,
        JomhorSpace.xl + MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Crest(name: widget.club.name, url: widget.club.crestUrl, size: 40),
              const SizedBox(width: JomhorSpace.m),
              Expanded(child: Text(l.clubJoinTitle(l.isolate(widget.club.name)), style: JomhorType.h2)),
            ],
          ),
          const SizedBox(height: JomhorSpace.m),
          Text(l.clubJoinBody, style: JomhorType.body.copyWith(color: JomhorColors.textSecondary)),
          const SizedBox(height: JomhorSpace.l),
          ClubOptIns(
            push: _push,
            whatsapp: _whatsapp,
            onPush: _sending ? null : (v) => setState(() => _push = v),
            onWhatsapp: _sending ? null : (v) => setState(() => _whatsapp = v),
          ),
          const SizedBox(height: JomhorSpace.xl),
          if (_error case final error?) ...[
            Text(
              error,
              key: const Key('club.joinError'),
              style: JomhorType.bodySmall.copyWith(color: JomhorColors.danger),
            ),
            const SizedBox(height: JomhorSpace.m),
          ],
          FilledButton(
            key: const Key('club.joinConfirm'),
            onPressed: _sending ? null : _join,
            child: _sending
                ? const SizedBox.square(dimension: 22, child: CircularProgressIndicator(strokeWidth: 2.5))
                : Text(l.clubJoinConfirm),
          ),
          if (dues != null) ...[
            const SizedBox(height: JomhorSpace.m),
            DuesPayButton(
              key: const Key('club.joinDues'),
              product: dues,
              label: l.clubJoinDues(l.dh(dues.chargeMad)),
              outlined: true,
              enabled: !_sending,
              pay: () => _pay(dues),
            ),
            const SizedBox(height: JomhorSpace.s),
            Text(
              l.clubDuesWhy,
              textAlign: TextAlign.center,
              style: JomhorType.caption.copyWith(color: JomhorColors.textMuted),
            ),
          ],
        ],
      ),
    );
  }
}

/// The two marketing opt-ins, one line each on what they send: in the join sheet and in the member's settings.
class ClubOptIns extends StatelessWidget {
  const new({required this.push, required this.whatsapp, required this.onPush, required this.onWhatsapp, super.key});

  final bool push;
  final bool whatsapp;
  final ValueChanged<bool>? onPush;
  final ValueChanged<bool>? onWhatsapp;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    Widget tile(Key key, String title, String body, ValueChanged<bool>? onChanged, {required bool value}) =>
        SwitchListTile(
          key: key,
          value: value,
          onChanged: onChanged,
          contentPadding: const EdgeInsets.symmetric(horizontal: JomhorSpace.l),
          title: Text(title, style: JomhorType.body.copyWith(fontWeight: FontWeight.w600)),
          subtitle: Text(body, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary)),
        );
    return Material(
      color: JomhorColors.surface2,
      shape: const RoundedRectangleBorder(
        side: BorderSide(color: JomhorColors.border),
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          tile(const Key('club.optin.push'), l.clubPushTitle, l.clubPushBody, onPush, value: push),
          const Divider(indent: JomhorSpace.l, endIndent: JomhorSpace.l),
          tile(const Key('club.optin.whatsapp'), l.clubWhatsappTitle, l.clubWhatsappBody, onWhatsapp, value: whatsapp),
        ],
      ),
    );
  }
}
