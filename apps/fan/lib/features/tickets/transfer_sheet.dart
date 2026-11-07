import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/auth/phone.dart';
import 'package:jomhor_fan/features/checkout/holder_name.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/transfer.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/hold_to_confirm.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// Screen 8 · Transférer, as a bottom sheet over screen 5. Resolves with the transfer on a 201, else null.
Future<TransferDone?> showTransferSheet(BuildContext context, FanTicket ticket) => showModalBottomSheet<TransferDone>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  showDragHandle: true,
  backgroundColor: JomhorColors.surface,
  shape: const RoundedRectangleBorder(
    borderRadius: BorderRadius.vertical(top: Radius.circular(JomhorRadius.cardLarge)),
  ),
  builder: (_) => TransferSheet(ticket: ticket),
);

enum _Step { form, confirm, failed }

/// Three calm steps, as on the web (W5): who (WhatsApp number + full name, checked as the server will), then a
/// confirm that names who and what can't be undone, sealed by a full hold; a refusal ends on its copy. A field the
/// server refuses (own number, name) sends the fan back to it.
class TransferSheet extends ConsumerStatefulWidget {
  const new({required this.ticket, super.key});

  final FanTicket ticket;

  @override
  ConsumerState<TransferSheet> createState() => _TransferSheetState();
}

class _TransferSheetState extends ConsumerState<TransferSheet> {
  final _phone = TextEditingController();
  final _name = TextEditingController();
  _Step _step = _Step.form;
  var _sending = false;
  String? _phoneError;
  String? _nameError;
  late String _failure;

  @override
  void dispose() {
    _phone.dispose();
    _name.dispose();
    super.dispose();
  }

  void _next() {
    final l = AppLocalizations.of(context);
    setState(() {
      _phoneError = normalizePhone(_phone.text) == null ? l.holderPhoneError(l.ltr(examplePhone)) : null;
      _nameError = normalizeHolderName(_name.text) == null ? l.holderNameError : null;
      if (_phoneError == null && _nameError == null) _step = _Step.confirm;
    });
  }

  Future<void> _send() async {
    final l = AppLocalizations.of(context);
    setState(() => _sending = true);
    final result = await ref.read(transferTicketProvider)(
      widget.ticket,
      toPhone: normalizePhone(_phone.text)!,
      name: normalizeHolderName(_name.text)!,
    );
    if (!mounted) return;
    final TransferRefused refused;
    switch (result) {
      case TransferDone():
        return Navigator.pop(context, result);
      case TransferRefused():
        refused = result;
    }
    final text = transferRefusalText(l, widget.ticket, refused);
    setState(() {
      _sending = false;
      switch (refused.reason) {
        case TransferRefusal.self || TransferRefusal.phone:
          _phoneError = text;
          _step = _Step.form;
        case TransferRefusal.name:
          _nameError = text;
          _step = _Step.form;
        case TransferRefusal.closed ||
            TransferRefusal.admitted ||
            TransferRefusal.notValid ||
            TransferRefusal.notFound ||
            TransferRefusal.unknown:
          _failure = text;
          _step = _Step.failed;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final t = widget.ticket;
    final title = switch (_step) {
      _Step.form => l.transferTitle,
      _ => l.transferConfirmTitle,
    };
    return PopScope(
      canPop: !_sending,
      child: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(
          JomhorSpace.xxl,
          0,
          JomhorSpace.xxl,
          JomhorSpace.xxl + MediaQuery.viewInsetsOf(context).bottom,
        ),
        child: AnimatedSwitcher(
          duration: motionOf(context, JomhorMotion.base),
          child: Column(
            key: ValueKey(_step),
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                l.transferEyebrow.toUpperCase(),
                style: JomhorType.label.copyWith(color: JomhorColors.accent).tracked(context),
              ),
              const SizedBox(height: JomhorSpace.s),
              Semantics(header: true, child: Text(title, style: JomhorType.h1.copyWith(fontSize: 30))),
              const SizedBox(height: JomhorSpace.xl),
              ...switch (_step) {
                _Step.form => _form(l, t),
                _Step.confirm => _confirm(l, t),
                _Step.failed => [
                  Semantics(
                    liveRegion: true,
                    child: _Panel(
                      key: const Key('transfer.failure'),
                      color: JomhorColors.danger,
                      icon: Icons.error_outline_rounded,
                      title: _failure,
                    ),
                  ),
                  const SizedBox(height: JomhorSpace.xxl),
                  OutlinedButton(
                    key: const Key('transfer.close'),
                    onPressed: () => Navigator.pop(context),
                    child: Text(l.close),
                  ),
                ],
              },
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _form(AppLocalizations l, FanTicket t) => [
    _Stub(ticket: t),
    const SizedBox(height: JomhorSpace.xl),
    TextField(
      key: const Key('transfer.phone'),
      controller: _phone,
      keyboardType: TextInputType.phone,
      textInputAction: TextInputAction.next,
      autocorrect: false,
      textDirection: TextDirection.ltr, // A phone number reads left to right in every locale.
      decoration: InputDecoration(
        labelText: l.transferPhone,
        helperText: l.transferPhoneHint,
        helperMaxLines: 2,
        errorText: _phoneError,
        errorMaxLines: 3,
        prefixIcon: const Icon(Icons.chat_outlined),
      ),
    ),
    const SizedBox(height: JomhorSpace.m),
    TextField(
      key: const Key('transfer.name'),
      controller: _name,
      textCapitalization: TextCapitalization.words,
      autocorrect: false,
      onSubmitted: (_) => _next(),
      decoration: InputDecoration(
        labelText: l.transferName,
        helperText: l.transferNameHint,
        helperMaxLines: 2,
        errorText: _nameError,
        errorMaxLines: 3,
        prefixIcon: const Icon(Icons.badge_outlined),
      ),
    ),
    if (t.transferUntil case final until?) ...[
      const SizedBox(height: JomhorSpace.l),
      Row(
        children: [
          const Icon(Icons.schedule_rounded, size: 16, color: JomhorColors.textMuted),
          const SizedBox(width: JomhorSpace.s),
          Expanded(
            child: Text(
              l.transferUntil(venueWhen(until, t.event.timezone, l.localeName)),
              style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
            ),
          ),
        ],
      ),
    ],
    const SizedBox(height: JomhorSpace.xxl),
    FilledButton(key: const Key('transfer.next'), onPressed: _next, child: Text(l.transferNext)),
  ];

  List<Widget> _confirm(AppLocalizations l, FanTicket t) {
    final name = normalizeHolderName(_name.text)!;
    final phone = normalizePhone(_phone.text)!;
    final muted = JomhorType.label.copyWith(color: JomhorColors.textMuted).tracked(context);
    Widget row(String label, Widget value) => Padding(
      padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.l, vertical: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label.toUpperCase(), style: muted),
          const SizedBox(width: JomhorSpace.l),
          Expanded(child: value),
        ],
      ),
    );
    return [
      DecoratedBox(
        decoration: const BoxDecoration(
          color: JomhorColors.ground,
          border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
          borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
        ),
        child: Column(
          children: [
            row(
              l.transferTo,
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    l.isolate(name),
                    key: const Key('transfer.confirmName'),
                    textAlign: TextAlign.end,
                    style: JomhorType.h2.copyWith(fontSize: 22),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    phone,
                    key: const Key('transfer.confirmPhone'),
                    textDirection: TextDirection.ltr,
                    style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            row(
              l.transferTicket,
              Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    t.event.title,
                    textAlign: TextAlign.end,
                    style: JomhorType.body.copyWith(fontWeight: FontWeight.w600),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    l.ticketRowFacts(t.zone.name, l.ltr(t.ticketNo)),
                    textAlign: TextAlign.end,
                    style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: JomhorSpace.l),
      _Panel(
        color: JomhorColors.accent,
        icon: Icons.qr_code_2_rounded,
        title: l.transferWarning(l.isolate(name)),
        body: l.transferFinal,
      ),
      const SizedBox(height: JomhorSpace.xxl),
      HoldToConfirm(
        key: const Key('transfer.hold'),
        label: l.transferHold,
        hint: l.transferHoldHint,
        busyLabel: _sending ? l.transferPending : null,
        onConfirmed: () => unawaited(_send()),
      ),
      const SizedBox(height: JomhorSpace.s),
      TextButton(
        key: const Key('transfer.edit'),
        onPressed: _sending ? null : () => setState(() => _step = _Step.form),
        child: Text(l.transferEdit),
      ),
    ];
  }
}

/// The ticket being given: event, zone and number.
class _Stub extends StatelessWidget {
  const new({required this.ticket});

  final FanTicket ticket;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.l, vertical: JomhorSpace.m),
      decoration: const BoxDecoration(
        color: JomhorColors.ground,
        border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
      ),
      child: Row(
        children: [
          const Icon(Icons.confirmation_number_outlined, size: 20, color: JomhorColors.accent),
          const SizedBox(width: JomhorSpace.m),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  ticket.event.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: JomhorType.body.copyWith(fontWeight: FontWeight.w600),
                ),
                Text(
                  l.ticketRowFacts(ticket.zone.name, l.ltr(ticket.ticketNo)),
                  style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// A tinted note: the warning before the hold (accent) or a refusal (danger).
class _Panel extends StatelessWidget {
  const new({required this.color, required this.icon, required this.title, this.body, super.key});

  final Color color;
  final IconData icon;
  final String title;
  final String? body;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(JomhorSpace.l),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.08),
      border: Border.all(color: color.withValues(alpha: 0.4)),
      borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.control)),
    ),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 20, color: color),
        const SizedBox(width: JomhorSpace.m),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: JomhorType.body.copyWith(
                  fontWeight: FontWeight.w600,
                  color: color == JomhorColors.danger ? color : JomhorColors.text,
                ),
              ),
              if (body case final body?) ...[
                const SizedBox(height: JomhorSpace.xs),
                Text(body, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary)),
              ],
            ],
          ),
        ),
      ],
    ),
  );
}
