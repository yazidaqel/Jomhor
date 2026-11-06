import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/features/auth/auth_repository.dart';
import 'package:jomhor_fan/features/profile/profile_controller.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// Profil tab (Lot 1): name (editable), phone (read-only), language (FR only), logout. Logout stays usable when
/// `/fan/me` cannot be read (offline).
class ProfileScreen extends ConsumerWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final me = ref.watch(profileProvider);
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(JomhorSpace.xl),
          children: [
            Text(l.navProfile, style: JomhorType.h1),
            const SizedBox(height: JomhorSpace.l),
            switch (me) {
              AsyncData(:final value) => _Identity(user: value),
              AsyncError(:final error) => ErrorPanel(error: error, onRetry: () => ref.invalidate(profileProvider)),
              _ => const Padding(
                padding: EdgeInsets.all(JomhorSpace.xl),
                child: Center(child: CircularProgressIndicator()),
              ),
            },
            const SizedBox(height: JomhorSpace.l),
            _Section(l.profileLanguage),
            ListTile(
              key: const Key('profile.lang.fr'),
              title: Text(l.languageFr),
              trailing: const Icon(Icons.check, color: JomhorColors.accent),
              selected: true,
            ),
            // Lot 4: AR (RTL) and EN copy.
            for (final name in [l.languageAr, l.languageEn])
              ListTile(enabled: false, title: Text(name), trailing: Text(l.chipSoon)),
            const SizedBox(height: JomhorSpace.xxl),
            const _Logout(),
          ],
        ),
      ),
    );
  }
}

class _Section extends StatelessWidget {
  const new(this.title);

  final String title;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: JomhorSpace.xs),
    child: Text(title.toUpperCase(), style: JomhorType.label.copyWith(color: JomhorColors.textMuted)),
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
        _Section(l.profileName),
        if (!_editing)
          ListTile(
            title: Text(
              name ?? l.profileNameEmpty,
              key: const Key('profile.name'),
              style: name == null ? const TextStyle(color: JomhorColors.textMuted) : null,
            ),
            trailing: IconButton(
              key: const Key('profile.editName'),
              tooltip: l.profileEditName,
              icon: const Icon(Icons.edit_outlined),
              onPressed: _edit,
            ),
          )
        else ...[
          TextField(
            key: const Key('profile.nameField'),
            controller: _name,
            autofocus: true,
            textCapitalization: TextCapitalization.words,
            autofillHints: const [AutofillHints.name],
            decoration: InputDecoration(labelText: l.holderNameLabel, errorText: _error, errorMaxLines: 3),
            onSubmitted: _saving ? null : (_) => _save(),
          ),
          const SizedBox(height: JomhorSpace.s),
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
        const SizedBox(height: JomhorSpace.l),
        _Section(l.profilePhone),
        ListTile(
          // Read-only: the phone is the login. An E.164 number reads left to right in every locale.
          title: Text(widget.user.phone, key: const Key('profile.phone'), textDirection: TextDirection.ltr),
        ),
      ],
    );
  }
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
        SizedBox(
          height: JomhorSize.ctaHeight,
          child: OutlinedButton.icon(
            key: const Key('profile.logout'),
            onPressed: _leaving ? null : () => _logout(all: false),
            icon: const Icon(Icons.logout),
            label: Text(l.profileLogout),
          ),
        ),
        const SizedBox(height: JomhorSpace.s),
        TextButton(
          key: const Key('profile.logoutAll'),
          onPressed: _leaving ? null : () => _logout(all: true),
          child: Text(l.profileLogoutAll),
        ),
      ],
    );
  }
}
