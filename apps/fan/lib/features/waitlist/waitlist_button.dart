import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/features/waitlist/waitlist_controller.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// The CTA of screens 1 and 2 for a sold-out event without a room (DESIGN.md Lot 5): "Liste d'attente" joins (signed
/// out: the login first, then the join); on the list, an outlined "Sur la liste d'attente" opens the sheet to leave.
/// Keys: `{scope}.waitlist` and `{scope}.waitlistOn`.
class WaitlistButton extends ConsumerStatefulWidget {
  const new({required this.eventId, required this.scope, this.loginFrom, super.key});

  final int eventId;
  final String scope;

  /// Where the login goes once signed in; null = back to this page (screen 2).
  final String? loginFrom;

  @override
  ConsumerState<WaitlistButton> createState() => _WaitlistButtonState();
}

class _WaitlistButtonState extends ConsumerState<WaitlistButton> {
  var _busy = false;

  WaitlistController get _controller => ref.read(waitlistProvider(widget.eventId).notifier);

  Future<void> _run(Future<void> Function() action) async {
    final l = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      await action();
    } on Object catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e is ApiError ? apiErrorText(l, e) : l.errorGeneric)));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _join() {
    if (ref.read(sessionProvider)) {
      unawaited(_run(_controller.join));
      return;
    }
    _controller.joinAfterLogin();
    // The container outlives this widget: a login left without a session forgets the join.
    final container = ProviderScope.containerOf(context, listen: false);
    final id = widget.eventId;
    unawaited(
      context
          .push(switch (widget.loginFrom) {
            final from? => Uri(path: '/login', queryParameters: {'from': from}).toString(),
            null => '/login',
          })
          .whenComplete(() {
            if (!container.read(sessionProvider)) container.read(waitlistJoinAfterLoginProvider).remove(id);
          }),
    );
  }

  Future<void> _sheet() async {
    final leave = await showModalBottomSheet<bool>(
      context: context,
      useSafeArea: true,
      showDragHandle: true,
      backgroundColor: JomhorColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(JomhorRadius.cardLarge)),
      ),
      builder: (_) => const _WaitlistSheet(),
    );
    if (leave == true && mounted) await _run(_controller.leave);
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final entry = ref.watch(waitlistProvider(widget.eventId));
    // The join remembered through the login failed: said once, on the page in front.
    ref.listen(waitlistProvider(widget.eventId), (_, next) {
      if (next case AsyncError(error: JoinAfterLoginFailed(:final error))
          when ModalRoute.of(context)?.isCurrent ?? true) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error is ApiError ? apiErrorText(l, error) : l.errorGeneric)));
      }
    });
    if (entry.value?.waiting ?? false) {
      return OutlinedButton.icon(
        key: Key('${widget.scope}.waitlistOn'),
        onPressed: _busy ? null : _sheet,
        style: OutlinedButton.styleFrom(side: const BorderSide(color: JomhorColors.accent)),
        icon: const Icon(Icons.check_rounded, size: 20, color: JomhorColors.accent),
        label: Text(l.waitlistOn, textAlign: TextAlign.center),
      );
    }
    return FilledButton(
      key: Key('${widget.scope}.waitlist'),
      onPressed: _busy || entry.isLoading ? null : _join,
      child: Text(l.waitlistJoin, textAlign: TextAlign.center),
    );
  }
}

/// "On te prévient dès que des places se libèrent. Premier arrivé, premier servi." + "Quitter la liste" (pops true).
class _WaitlistSheet extends StatelessWidget {
  const new();

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, 0, JomhorSpace.xl, JomhorSpace.xl),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              const Icon(Icons.check_circle_rounded, size: 24, color: JomhorColors.accent),
              const SizedBox(width: JomhorSpace.s),
              Expanded(child: Text(l.waitlistOn, style: JomhorType.h2)),
            ],
          ),
          const SizedBox(height: JomhorSpace.m),
          Text(
            l.waitlistSheetBody,
            key: const Key('waitlist.sheet'),
            style: JomhorType.body.copyWith(color: JomhorColors.textSecondary),
          ),
          const SizedBox(height: JomhorSpace.xl),
          OutlinedButton(
            key: const Key('waitlist.leave'),
            onPressed: () => Navigator.pop(context, true),
            child: Text(l.waitlistLeave),
          ),
        ],
      ),
    );
  }
}
