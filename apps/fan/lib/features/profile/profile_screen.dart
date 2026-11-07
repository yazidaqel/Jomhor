import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/app/locale.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/features/auth/auth_repository.dart';
import 'package:jomhor_fan/features/profile/profile_controller.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/features/tickets/transfer.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Profil tab: name (editable), phone (read-only), language (FR, AR, EN: saved on the phone and sent to the server),
/// "Téléphone perdu ?" (re-issue), logout. Logout stays usable when `/fan/me` cannot be read (offline).
class ProfileScreen extends ConsumerWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final me = ref.watch(profileProvider);
    return Scaffold(
      body: SafeArea(
        bottom: false,
        // Short page: built whole (not lazily), so logout is there even under a tall error panel.
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.l, JomhorSpace.xl, JomhorSpace.xxl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const BrandBar(),
              const SizedBox(height: JomhorSpace.s),
              Text(l.navProfile, style: JomhorType.h1.copyWith(fontSize: 34)),
              const SizedBox(height: JomhorSpace.xl),
              switch (me) {
                AsyncData(:final value) => Entrance(child: _Identity(user: value)),
                AsyncError(:final error) => ErrorPanel(error: error, onRetry: () => ref.invalidate(profileProvider)),
                _ => const _IdentitySkeleton(),
              },
              const SizedBox(height: JomhorSpace.xxl),
              Entrance(
                index: 1,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    SectionLabel(l.profileLanguage),
                    _Group(
                      children: [
                        for (final code in appLocales)
                          ListTile(
                            key: Key('profile.lang.$code'),
                            title: Text(switch (code) {
                              'ar' => l.languageAr,
                              'en' => l.languageEn,
                              _ => l.languageFr,
                            }),
                            trailing: code == l.localeName
                                ? const Icon(Icons.check_rounded, color: JomhorColors.accent)
                                : null,
                            selected: code == l.localeName,
                            onTap: () => ref.read(appLocaleProvider.notifier).select(code),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: JomhorSpace.xxl),
              const Entrance(index: 2, child: _LostPhone()),
              const SizedBox(height: JomhorSpace.xxl),
              const Entrance(index: 3, child: _Logout()),
              const SizedBox(height: JomhorSpace.xxl * 2),
              const _Footer(),
            ],
          ),
        ),
      ),
    );
  }
}

/// The Jomhor lockup and the app version, at the foot of Profil.
class _Footer extends ConsumerWidget {
  const new();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final version = ref.watch(appVersionProvider).value;
    return Column(
      children: [
        const JomhorLockup(height: 30),
        if (version != null) ...[
          const SizedBox(height: JomhorSpace.xs),
          Text(
            l.profileVersion(l.ltr(version)),
            key: const Key('profile.version'),
            style: JomhorType.caption.copyWith(color: JomhorColors.textMuted),
          ),
        ],
      ],
    );
  }
}

/// Rows in one card, with hairlines between them (settings group).
class _Group extends StatelessWidget {
  const new({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Card(
    shape: const RoundedRectangleBorder(
      side: BorderSide(color: JomhorColors.border),
      borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
    ),
    clipBehavior: Clip.antiAlias,
    child: Column(
      children: [
        for (final (i, child) in children.indexed) ...[
          if (i > 0) const Divider(indent: JomhorSpace.l, endIndent: JomhorSpace.l),
          child,
        ],
      ],
    ),
  );
}

class _IdentitySkeleton extends StatelessWidget {
  const new();

  @override
  Widget build(BuildContext context) => Semantics(
    label: AppLocalizations.of(context).loading,
    child: const ExcludeSemantics(
      child: Row(
        children: [
          Skeleton(height: 72, width: 72, radius: JomhorRadius.pill),
          SizedBox(width: JomhorSpace.l),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                FractionallySizedBox(widthFactor: 0.6, child: Skeleton(height: 22)),
                SizedBox(height: JomhorSpace.s),
                FractionallySizedBox(widthFactor: 0.45, child: Skeleton(height: 12)),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

class _Identity extends ConsumerStatefulWidget {
  const new({required this.user});

  final FanUser user;

  @override
  ConsumerState<_Identity> createState() => _IdentityState();
}

class _IdentityState extends ConsumerState<_Identity> {
  final _name = TextEditingController();
  var _editing = false;
  var _saving = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  void _edit() => setState(() {
    _name.text = widget.user.displayName ?? '';
    _error = null;
    _editing = true;
  });

  Future<void> _save() async {
    final l = AppLocalizations.of(context);
    setState(() => _saving = true);
    final e = await ref.read(profileProvider.notifier).saveName(_name.text);
    if (!mounted) return;
    setState(() {
      _saving = false;
      _editing = e != null;
      _error = e == null ? null : (isBadName(e) ? l.holderNameError : apiErrorText(l, e));
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final name = widget.user.displayName;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          padding: const EdgeInsets.all(JomhorSpace.xl),
          decoration: const BoxDecoration(
            gradient: LinearGradient(
              begin: AlignmentDirectional.topStart,
              end: AlignmentDirectional.bottomEnd,
              colors: [JomhorColors.surface2, JomhorColors.surface],
            ),
            border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
            borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.card)),
          ),
          child: Row(
            children: [
              _Avatar(initials: initials(name)),
              const SizedBox(width: JomhorSpace.l),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      name == null ? l.profileNameEmpty : l.isolate(name),
                      key: const Key('profile.name'),
                      semanticsLabel: l.dotPair(l.profileName, name ?? l.profileNameEmpty),
                      style: name == null
                          ? JomhorType.body.copyWith(color: JomhorColors.textMuted)
                          : JomhorType.h2.copyWith(fontSize: 24),
                    ),
                    const SizedBox(height: JomhorSpace.xs),
                    // Read-only: the phone is the login. An E.164 number reads left to right in every locale.
                    Text(
                      widget.user.phone,
                      key: const Key('profile.phone'),
                      semanticsLabel: l.dotPair(l.profilePhone, widget.user.phone),
                      textDirection: TextDirection.ltr,
                      style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                    ),
                  ],
                ),
              ),
              if (!_editing)
                IconButton(
                  key: const Key('profile.editName'),
                  tooltip: l.profileEditName,
                  icon: const Icon(Icons.edit_outlined, size: 20),
                  onPressed: _edit,
                ),
            ],
          ),
        ),
        AnimatedSize(
          duration: motionOf(context, JomhorMotion.base),
          curve: Curves.easeOut,
          alignment: AlignmentDirectional.topCenter,
          child: !_editing
              ? const SizedBox(width: double.infinity)
              : Padding(
                  padding: const EdgeInsets.only(top: JomhorSpace.l),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      TextField(
                        key: const Key('profile.nameField'),
                        controller: _name,
                        autofocus: true,
                        textCapitalization: TextCapitalization.words,
                        autofillHints: const [AutofillHints.name],
                        decoration: InputDecoration(
                          labelText: l.holderNameLabel,
                          errorText: _error,
                          errorMaxLines: 3,
                          prefixIcon: const Icon(Icons.badge_outlined),
                        ),
                        onSubmitted: _saving ? null : (_) => _save(),
                      ),
                      const SizedBox(height: JomhorSpace.m),
                      Row(
                        children: [
                          Expanded(
                            child: OutlinedButton(
                              onPressed: _saving ? null : () => setState(() => _editing = false),
                              child: Text(l.cancel),
                            ),
                          ),
                          const SizedBox(width: JomhorSpace.s),
                          Expanded(
                            child: FilledButton(
                              key: const Key('profile.saveName'),
                              onPressed: _saving ? null : _save,
                              child: Text(l.save),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
        ),
      ],
    );
  }
}

/// The fan's initials on an accent disc (or a person glyph without a name).
class _Avatar extends StatelessWidget {
  const new({required this.initials});

  final String? initials;

  @override
  Widget build(BuildContext context) => Container(
    width: 64,
    height: 64,
    alignment: Alignment.center,
    decoration: BoxDecoration(
      shape: BoxShape.circle,
      color: JomhorColors.accent,
      boxShadow: [BoxShadow(color: JomhorColors.accent.withValues(alpha: 0.3), blurRadius: 24)],
    ),
    child: initials == null
        ? const Icon(Icons.person_rounded, color: JomhorColors.onAccent, size: 32)
        : Text(
            initials!,
            textScaler: TextScaler.noScaling,
            style: JomhorType.number.copyWith(color: JomhorColors.onAccent, fontSize: 28),
          ),
  );
}

class _Logout extends ConsumerStatefulWidget {
  const new();

  @override
  ConsumerState<_Logout> createState() => _LogoutState();
}

class _LogoutState extends ConsumerState<_Logout> {
  var _leaving = false;

  Future<void> _logout({required bool all}) async {
    final l = AppLocalizations.of(context);
    // Above the router: still there once the session ends and the login screen shows.
    final messenger = ScaffoldMessenger.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(all ? l.profileLogoutAll : l.profileLogout),
        content: Text(all ? l.profileLogoutAllBody : l.profileLogoutBody),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: Text(l.cancel)),
          FilledButton(
            key: const Key('profile.logoutConfirm'),
            style: FilledButton.styleFrom(minimumSize: const Size(JomhorSize.touchMin, 48)),
            onPressed: () => Navigator.pop(context, true),
            child: Text(l.profileLogoutConfirm),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _leaving = true);
    final revoked = await ref.read(sessionProvider.notifier).logout(all: all);
    // This phone is signed out either way; say so only when the other devices may still be signed in.
    if (all && !revoked) messenger.showSnackBar(SnackBar(content: Text(l.profileLogoutAllFailed)));
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        OutlinedButton.icon(
          key: const Key('profile.logout'),
          onPressed: _leaving ? null : () => _logout(all: false),
          icon: const Icon(Icons.logout_rounded, size: 20),
          label: Text(l.profileLogout),
        ),
        const SizedBox(height: JomhorSpace.xs),
        TextButton(
          key: const Key('profile.logoutAll'),
          onPressed: _leaving ? null : () => _logout(all: true),
          child: Text(l.profileLogoutAll, textAlign: TextAlign.center),
        ),
      ],
    );
  }
}

/// "Téléphone perdu ?" (DESIGN.md Lot 4, Profil): a confirm listing the upcoming tickets that can be re-issued, then
/// a new ticket id and K_t for each (`POST /fan/tickets/{id}/reissue`) and the list fetched again. The old QR stop
/// passing from the next manifest. The server also revokes the fan's other sessions, so the lost phone is signed out
/// and cannot fetch the new keys; this one stays signed in.
class _LostPhone extends ConsumerStatefulWidget {
  const new();

  @override
  ConsumerState<_LostPhone> createState() => _LostPhoneState();
}

class _LostPhoneState extends ConsumerState<_LostPhone> {
  var _busy = false;

  Future<void> _open() async {
    final l = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final snapshot = (await ref.read(ticketsProvider.future)).snapshot;
    if (!mounted) return;
    final list = snapshot == null ? <FanTicket>[] : reissuable(snapshot, ref.read(transferRefusalsProvider));
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l.profileLostPhone),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l.lostPhoneBody),
              if (list.isNotEmpty) ...[
                const SizedBox(height: JomhorSpace.m),
                Text(l.lostPhoneSignedOut, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary)),
              ],
              const SizedBox(height: JomhorSpace.l),
              if (list.isEmpty)
                Text(l.lostPhoneNone, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted))
              else
                for (final t in list)
                  Padding(
                    key: Key('lostPhone.ticket.${t.id}'),
                    padding: const EdgeInsets.only(bottom: JomhorSpace.s),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Icon(Icons.confirmation_number_outlined, size: 20, color: JomhorColors.accent),
                        const SizedBox(width: JomhorSpace.m),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(t.event.title, style: JomhorType.body.copyWith(fontWeight: FontWeight.w600)),
                              Text(
                                l.ticketRowFacts(t.zone.name, l.ltr(t.ticketNo)),
                                style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: Text(list.isEmpty ? l.close : l.cancel)),
          if (list.isNotEmpty)
            FilledButton(
              key: const Key('lostPhone.confirm'),
              style: FilledButton.styleFrom(minimumSize: const Size(JomhorSize.touchMin, 48)),
              onPressed: () => Navigator.pop(context, true),
              child: Text(l.lostPhoneConfirm),
            ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _busy = true);
    final result = await ref.read(reissueTicketsProvider)(list);
    if (mounted) setState(() => _busy = false);
    messenger.showSnackBar(
      SnackBar(content: Text(result.failed == 0 ? l.lostPhoneDone(result.done) : l.lostPhoneFailed)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return _Group(
      children: [
        ListTile(
          key: const Key('profile.lostPhone'),
          enabled: !_busy,
          leading: const Icon(Icons.phonelink_erase_rounded, color: JomhorColors.accent),
          title: Text(l.profileLostPhone),
          subtitle: Text(l.profileLostPhoneHint),
          trailing: _busy
              ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.chevron_right_rounded),
          onTap: _open,
        ),
      ],
    );
  }
}
