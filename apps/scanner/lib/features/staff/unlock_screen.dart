import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/core/event_time.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/staff/staff_session.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

/// Déverrouillage (DESIGN Lot 2 adjustments): pick a name, then the 4-digit PIN on a pad of >= 44 px keys.
/// Works offline: the PIN is checked on the device (plan lot-2 Q31).
class UnlockScreen extends ConsumerStatefulWidget {
  const new({required this.timeZone, super.key});

  /// The event zone, for the lockout end time.
  final String? timeZone;

  @override
  ConsumerState<UnlockScreen> createState() => _UnlockScreenState();
}

class _UnlockScreenState extends ConsumerState<UnlockScreen> {
  static const pinLength = 4;

  StaffData? _staff;
  String _pin = '';
  bool _busy = false;
  String? _message;

  Future<void> _digit(String d) async {
    if (_busy || _pin.length >= pinLength) return;
    setState(() => _pin += d);
    if (_pin.length < pinLength) return;
    setState(() => _busy = true);
    final l = AppLocalizations.of(context);
    String? message;
    try {
      final outcome = await ref.read(staffSessionProvider).unlock(_staff!.staffId!, _pin);
      if (!outcome.ok) {
        message = outcome.lockedUntilMs == null
            ? l.unlockWrong(outcome.attemptsLeft)
            : l.unlockLocked(shortTime(outcome.lockedUntilMs!, widget.timeZone));
      }
    } on Object {
      message = l.errorGeneric;
    } finally {
      // Never left busy; on success the home screen replaces this one.
      if (mounted) {
        setState(() {
          _busy = false;
          _pin = '';
          _message = message;
        });
      }
    }
  }

  void _delete() {
    if (_busy || _pin.isEmpty) return;
    setState(() => _pin = _pin.substring(0, _pin.length - 1));
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final staff = _staff;
    return Scaffold(
      appBar: AppBar(title: Text(l.unlockTitle)),
      body: SafeArea(child: staff == null ? _names(l) : _pad(l, staff)),
    );
  }

  Widget _names(AppLocalizations l) {
    final list = ref.watch(staffListProvider).value ?? const [];
    return ListView(
      padding: const EdgeInsets.all(JomhorSpace.xxl),
      children: [
        Text(list.isEmpty ? l.unlockNoStaff : l.unlockPickName, style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: JomhorSpace.l),
        for (final s in list)
          ListTile(
            minTileHeight: JomhorSize.ctaHeight,
            title: Text(s.label, style: JomhorType.h2),
            subtitle: Text(s.role == 'SUPERVISOR' ? l.roleSupervisor : l.roleGate),
            trailing: const Icon(Icons.chevron_right_rounded),
            onTap: () => setState(() {
              _staff = s;
              _pin = '';
              _message = null;
            }),
          ),
      ],
    );
  }

  Widget _pad(AppLocalizations l, StaffData staff) {
    Widget key(Widget child, VoidCallback? onPressed, {String? semantics}) => Expanded(
      child: Padding(
        padding: const EdgeInsets.all(JomhorSpace.xs),
        child: Semantics(
          label: semantics,
          button: true,
          child: OutlinedButton(
            style: OutlinedButton.styleFrom(
              minimumSize: const Size(JomhorSize.touchMin, JomhorSize.ctaHeight),
              foregroundColor: JomhorColors.text,
              side: const BorderSide(color: JomhorColors.border),
              shape: const RoundedRectangleBorder(
                borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
              ),
            ),
            onPressed: onPressed,
            child: child,
          ),
        ),
      ),
    );
    Widget digit(String d) => key(Text(d, style: JomhorType.number), () => _digit(d));
    return ListView(
      padding: const EdgeInsets.all(JomhorSpace.xxl),
      children: [
        Text(l.unlockPin(staff.label), style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: JomhorSpace.xl),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < pinLength; i++)
              Container(
                margin: const EdgeInsets.symmetric(horizontal: JomhorSpace.s),
                width: JomhorSpace.l,
                height: JomhorSpace.l,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: i < _pin.length ? JomhorColors.accent : null,
                  border: Border.all(color: JomhorColors.borderMuted),
                ),
              ),
          ],
        ),
        const SizedBox(height: JomhorSpace.l),
        SizedBox(
          height: JomhorSpace.xxl,
          child: _busy
              ? const Center(child: LinearProgressIndicator())
              : Text(
                  _message ?? '',
                  textAlign: TextAlign.center,
                  style: JomhorType.body.copyWith(color: JomhorColors.danger),
                ),
        ),
        const SizedBox(height: JomhorSpace.l),
        // Digits stay left-to-right in Arabic too, like a phone keypad.
        Directionality(
          textDirection: TextDirection.ltr,
          child: Column(
            children: [
              for (final row in const [
                ['1', '2', '3'],
                ['4', '5', '6'],
                ['7', '8', '9'],
              ])
                Row(children: [for (final d in row) digit(d)]),
              Row(
                children: [
                  const Expanded(child: SizedBox.shrink()),
                  digit('0'),
                  key(const Icon(Icons.backspace_outlined), _delete, semantics: l.unlockDelete),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: JomhorSpace.l),
        TextButton(onPressed: _busy ? null : () => setState(() => _staff = null), child: Text(l.unlockChangeName)),
      ],
    );
  }
}
