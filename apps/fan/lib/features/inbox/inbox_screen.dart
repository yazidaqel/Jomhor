import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/features/inbox/inbox.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Screen 10 · Notifications: one inbox across clubs, each row led by its club line (DESIGN.md Lot 4 and
/// §Multi-club adjustments). Showing the list marks it seen (the bell's dot goes).
class InboxScreen extends ConsumerStatefulWidget {
  const new({super.key});

  @override
  ConsumerState<InboxScreen> createState() => _InboxScreenState();
}

class _InboxScreenState extends ConsumerState<InboxScreen> {
  @override
  void initState() {
    super.initState();
    ref.listenManual(inboxProvider, fireImmediately: true, (_, next) {
      if (next case AsyncData(:final value)) {
        if (newestSentAt(value) case final newest?) unawaited(ref.read(inboxSeenProvider.notifier).saw(newest));
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final inbox = ref.watch(inboxProvider);
    final now = ref.watch(clockProvider).value ?? ref.read(nowProvider)();
    return Scaffold(
      appBar: AppBar(
        leading: BackButton(onPressed: () => context.canPop() ? context.pop() : context.go('/')),
        title: Text(l.inboxTitle),
      ),
      body: RefreshIndicator(
        color: JomhorColors.accent,
        backgroundColor: JomhorColors.surface,
        onRefresh: () => ref.refresh(inboxProvider.future),
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.s, JomhorSpace.xl, JomhorSpace.xxl),
          children: switch (inbox) {
            AsyncValue(hasError: true, :final error?) => [
              ErrorPanel(error: error, onRetry: () => ref.invalidate(inboxProvider)),
            ],
            AsyncValue(hasValue: true, :final value?) when value.isEmpty => [
              StatePanel(key: const Key('inbox.empty'), message: l.inboxEmpty),
            ],
            AsyncValue(hasValue: true, :final value?) => [
              for (final (i, m) in value.indexed)
                Entrance(index: i, child: _Row(message: m, now: now)),
            ],
            _ => const [SkeletonList(rows: 4)],
          },
        ),
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const new({required this.message, required this.now});

  final InboxMessage message;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final m = message;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: TapCard(
        key: Key('inbox.row.${m.id}'),
        onTap: switch (m.eventId) {
          final id? => () => context.push('/events/$id'),
          null => null,
        },
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: ClubLine(name: m.club.name, crestUrl: m.club.crestUrl)),
                const SizedBox(width: JomhorSpace.s),
                Text(sentAgo(l, now, m.sentAt), style: JomhorType.caption.copyWith(color: JomhorColors.textMuted)),
              ],
            ),
            const SizedBox(height: JomhorSpace.xs),
            Text(m.title, style: JomhorType.body.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 2),
            Text(m.body, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary)),
          ],
        ),
      ),
    );
  }
}

/// "À l'instant", "il y a 5 min", "il y a 3 h", "hier", "il y a 4 j". A clock behind the server reads as now.
String sentAgo(AppLocalizations l, DateTime now, DateTime sentAt) {
  final d = now.difference(sentAt);
  if (d.inMinutes < 1) return l.inboxJustNow;
  if (d.inHours < 1) return l.inboxMinutesAgo(d.inMinutes);
  if (d.inDays < 1) return l.inboxHoursAgo(d.inHours);
  return l.inboxDaysAgo(d.inDays);
}
