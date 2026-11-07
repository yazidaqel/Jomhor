import 'package:flutter/material.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

/// The 4-digit PIN pad of Déverrouillage and of the supervisor sheet: keys of >= 44 px, digits left-to-right in
/// Arabic too. [onPin] checks a full PIN and returns the message to show, null on success (the parent moves on).
class PinPad extends StatefulWidget {
  const new({required this.title, required this.onPin, required this.backLabel, required this.onBack, super.key});

  static const pinLength = 4;

  final String title;
  final Future<String?> Function(String pin) onPin;
  final String backLabel;
  final VoidCallback onBack;

  @override
  State<PinPad> createState() => _PinPadState();
}

class _PinPadState extends State<PinPad> {
  String _pin = '';
  bool _busy = false;
  String? _message;

  Future<void> _digit(String d) async {
    if (_busy || _pin.length >= PinPad.pinLength) return;
    setState(() => _pin += d);
    if (_pin.length < PinPad.pinLength) return;
    setState(() => _busy = true);
    final l = AppLocalizations.of(context);
    String? message;
    try {
      message = await widget.onPin(_pin);
    } on Object {
      message = l.errorGeneric;
    } finally {
      // Never left busy; on success the parent replaces this pad.
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
        Text(widget.title, style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: JomhorSpace.xl),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < PinPad.pinLength; i++)
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
        TextButton(onPressed: _busy ? null : widget.onBack, child: Text(widget.backLabel)),
      ],
    );
  }
}
