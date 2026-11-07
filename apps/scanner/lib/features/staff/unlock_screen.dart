import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/core/event_time.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/staff/pin_pad.dart';
import 'package:jomhor_scanner/features/staff/staff_session.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';
import 'package:jomhor_scanner/ui/brand.dart';

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
  StaffData? _staff;

  /// Null on success: the home screen replaces this one.
  Future<String?> _check(String pin) async {
    final l = AppLocalizations.of(context);
    final outcome = await ref.read(staffSessionProvider).unlock(_staff!.staffId!, pin);
    if (outcome.ok) return null;
    return outcome.lockedUntilMs == null
        ? l.unlockWrong(outcome.attemptsLeft)
        : l.unlockLocked(shortTime(outcome.lockedUntilMs!, widget.timeZone));
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final staff = _staff;
    return Scaffold(
      appBar: brandAppBar(l.unlockTitle),
      body: SafeArea(
        child: staff == null
            ? _names(l)
            : PinPad(
                key: ValueKey(staff.staffId),
                title: l.unlockPin(staff.label),
                onPin: _check,
                backLabel: l.unlockChangeName,
                onBack: () => setState(() => _staff = null),
              ),
      ),
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
            onTap: () => setState(() => _staff = s),
          ),
      ],
    );
  }
}
