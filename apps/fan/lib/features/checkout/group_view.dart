import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/seen_set.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/checkout/holder_name.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/checkout/order_status.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:url_launcher/url_launcher.dart';

const _seenKey = 'group.seen';

/// Before the event read lands (it carries the venue zone): Morocco's, the zone of every venue so far (lot-4 Q97).
const _homeZone = 'Africa/Casablanca';

/// A member's deadline, the clock time in the venue zone.
String _due(AppLocalizations l, GroupMember m, String zone) => switch (m.reservedUntil) {
  final until? => venueClock(until, zone, l.localeName),
  null => '',
};

/// Member order ids whose paid check already settled on this phone (lot-4 Q108); wiped on sign-out.
final groupSeenProvider = AsyncNotifierProvider<SeenSet, Set<String>>(() => SeenSet(_seenKey));

/// Never throws.
Future<void> wipeGroupSeen() => wipeSeen(_seenKey);

/// Opens a link in another app (WhatsApp). Tests override it.
final openExternalProvider = Provider<Future<void> Function(Uri url)>(
  (ref) => (url) async {
    try {
      await launchUrl(url, mode: LaunchMode.externalApplication);
    } on Object {
      // No app for it: "Copier le lien" is right there.
    }
  },
);

/// "Relancer sur WhatsApp": WhatsApp's share link with the copy and the friend's [GroupMember.payUrl]. No phone: the
/// fan picks the chat (a member's number never reaches the app).
Uri remindUrl(AppLocalizations l, GroupMember m, {required String eventTitle, required String timezone}) {
  final time = _due(l, m, timezone);
  return Uri.parse('https://wa.me/?text=${Uri.encodeComponent(l.groupRemindText(eventTitle, time, m.payUrl))}');
}

/// Screen 9, initiator (DESIGN.md Lot 4 and wow table): the buyer has paid; "{paid}/{total} payées" rolls up as
/// friends pay, one row card per friend with their status, and a nudge for each one still to pay. [OrderStatus]
/// polls every 10 s while a friend can still pay.
class GroupView extends ConsumerWidget {
  const new({required this.view, super.key});

  final OrderView view;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final order = view.order;
    final event = switch (order.eventId) {
      final id? => ref.watch(eventDetailProvider(id)).value?.event,
      null => null,
    };
    final seen = ref.watch(groupSeenProvider).value;
    // Read once on this phone: until then nobody knows which checks are new.
    if (seen == null) return const Center(child: CircularProgressIndicator());
    final (:paid, :total) = groupPlaces(order);
    return ListView(
      padding: const EdgeInsets.all(JomhorSpace.xl),
      children: [
        Text(
          event == null ? l.groupBand : l.dotPair(l.groupBand, l.isolate(event.title)),
          key: const Key('group.title'),
          style: JomhorType.h2,
        ),
        const SizedBox(height: JomhorSpace.l),
        _PaidFigure(paid: paid, total: total),
        const SizedBox(height: JomhorSpace.xxl),
        for (final m in order.group!.members) ...[
          _MemberCard(
            member: m,
            fresh: m.paid && !seen.contains(m.orderId),
            eventTitle: event?.title,
            timezone: event?.timezone,
          ),
          const SizedBox(height: 10),
        ],
        const SizedBox(height: JomhorSpace.l),
        FilledButton(
          key: const Key('order.seeTickets'),
          onPressed: () {
            unawaited(ref.read(ticketsProvider.notifier).refresh());
            context.go('/tickets');
          },
          child: Text(l.seeTickets),
        ),
      ],
    );
  }
}

/// "2/4 payées" in display type; the paid count rolls when it changes.
class _PaidFigure extends StatelessWidget {
  const new({required this.paid, required this.total});

  final int paid;
  final int total;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final figure = JomhorType.scanResult.copyWith(fontFeatures: const [FontFeature.tabularFigures()]);
    return Semantics(
      label: l.groupPaid(paid, total),
      excludeSemantics: true,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.baseline,
        textBaseline: TextBaseline.alphabetic,
        children: [
          // Figures read left to right in every locale.
          Row(
            key: const Key('group.figure'),
            textDirection: TextDirection.ltr,
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              ClipRect(
                child: Roll(
                  value: paid,
                  child: Text(
                    '$paid',
                    key: const Key('group.paid'),
                    textDirection: TextDirection.ltr,
                    style: figure.copyWith(color: JomhorColors.accent),
                  ),
                ),
              ),
              Text('/$total', key: const Key('group.total'), textDirection: TextDirection.ltr, style: figure),
            ],
          ),
          const SizedBox(width: JomhorSpace.m),
          Text(l.groupPaidWord, style: JomhorType.h1.copyWith(color: JomhorColors.textSecondary)),
        ],
      ),
    );
  }
}

/// One friend: initials avatar, label, amount, status chip; while unpaid, "Relancer sur WhatsApp" and "Copier le
/// lien" with their own pay link.
class _MemberCard extends ConsumerWidget {
  const new({required this.member, required this.fresh, this.eventTitle, this.timezone});

  final GroupMember member;
  final bool fresh;
  final String? eventTitle;
  final String? timezone;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final m = member;
    final zone = timezone;
    return Container(
      key: Key('group.member.${m.orderId}'),
      padding: const EdgeInsets.all(14),
      decoration: const BoxDecoration(
        color: JomhorColors.surface,
        border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Container(
                width: 40,
                height: 40,
                alignment: Alignment.center,
                decoration: const BoxDecoration(color: JomhorColors.surface2, shape: BoxShape.circle),
                child: Text(
                  labelInitials(m.holderLabel),
                  key: Key('group.initials.${m.orderId}'),
                  style: JomhorType.number.copyWith(fontSize: 16, color: JomhorColors.text),
                ),
              ),
              const SizedBox(width: JomhorSpace.m),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      l.isolate(m.holderLabel),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: JomhorType.body.copyWith(fontWeight: FontWeight.w600),
                    ),
                    Text(l.dh(m.totalMad), style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted)),
                  ],
                ),
              ),
              const SizedBox(width: JomhorSpace.s),
              _StatusChip(member: m, fresh: fresh, timezone: zone ?? _homeZone),
            ],
          ),
          if (!m.settled) ...[
            const SizedBox(height: JomhorSpace.m),
            Wrap(
              spacing: JomhorSpace.s,
              runSpacing: JomhorSpace.s,
              children: [
                if (eventTitle != null && zone != null)
                  FilledButton.tonalIcon(
                    key: Key('group.remind.${m.orderId}'),
                    style: FilledButton.styleFrom(minimumSize: const Size(0, JomhorSize.touchMin)),
                    onPressed: () =>
                        ref.read(openExternalProvider)(remindUrl(l, m, eventTitle: eventTitle!, timezone: zone)),
                    icon: const Icon(Icons.chat_outlined, size: 18),
                    label: Text(l.groupRemind),
                  ),
                OutlinedButton.icon(
                  key: Key('group.copy.${m.orderId}'),
                  style: OutlinedButton.styleFrom(minimumSize: const Size(0, JomhorSize.touchMin)),
                  onPressed: () async {
                    await Clipboard.setData(ClipboardData(text: m.payUrl));
                    if (context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l.groupLinkCopied)));
                    }
                  },
                  icon: const Icon(Icons.link_rounded, size: 18),
                  label: Text(l.groupCopyLink),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

/// "À payer · avant 21:30" / "Payé" with the check (settling in once when [fresh]) / "Expiré".
class _StatusChip extends StatelessWidget {
  const new({required this.member, required this.fresh, required this.timezone});

  final GroupMember member;
  final bool fresh;
  final String timezone;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final m = member;
    final (text, fg, bg) = switch (m) {
      GroupMember(paid: true) => (l.groupPaidChip, JomhorColors.successText, JomhorColors.successBg),
      GroupMember(settled: true) => (l.groupExpiredChip, JomhorColors.textMuted, JomhorColors.ground),
      _ => (
        l.groupDue(l.ltr(_due(l, m, timezone))),
        JomhorColors.accentHover,
        JomhorColors.accent.withValues(alpha: 0.12),
      ),
    };
    return Container(
      key: Key('group.chip.${m.orderId}'),
      padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 10),
      decoration: BoxDecoration(color: bg, borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.chip))),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (m.paid) ...[_SettleCheck(orderId: m.orderId, fresh: fresh), const SizedBox(width: JomhorSpace.xs)],
          Text(
            text,
            style: JomhorType.caption.copyWith(color: fg, fontSize: 12, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}

/// The paid check: when [fresh] it settles in once (scale and fade over `motion.ticketFlip`, off under reduced
/// motion), then its member id is saved in [groupSeenProvider] so a re-open shows it still.
class _SettleCheck extends ConsumerStatefulWidget {
  const new({required this.orderId, required this.fresh});

  final String orderId;
  final bool fresh;

  @override
  ConsumerState<_SettleCheck> createState() => _SettleCheckState();
}

class _SettleCheckState extends ConsumerState<_SettleCheck> with SingleTickerProviderStateMixin {
  late final _settle = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: JomhorMotion.ticketFlip),
    value: 1,
  );
  var _started = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _start();
  }

  @override
  void didUpdateWidget(_SettleCheck old) {
    super.didUpdateWidget(old);
    _start();
  }

  void _start() {
    if (!widget.fresh || _started) return;
    _started = true;
    if (!reduceMotion(context)) _settle.forward(from: 0);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(ref.read(groupSeenProvider.notifier).add([widget.orderId]));
    });
  }

  @override
  void dispose() {
    _settle.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _settle,
    builder: (context, child) {
      final t = Curves.easeOutBack.transform(_settle.value);
      return Opacity(
        key: _settle.isAnimating ? Key('group.settling.${widget.orderId}') : null,
        opacity: _settle.value,
        child: Transform.scale(scale: 0.4 + 0.6 * t, child: child),
      );
    },
    child: const Icon(Icons.check_circle_rounded, size: 16, color: JomhorColors.successText),
  );
}
