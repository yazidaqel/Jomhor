import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/features/auth/login_controller.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// OTP login: phone step, then code step (state in [LoginController]).
class LoginScreen extends ConsumerStatefulWidget {
  const new({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _phone = TextEditingController();
  final _code = TextEditingController();

  @override
  void dispose() {
    _phone.dispose();
    _code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final state = ref.watch(loginControllerProvider);
    final controller = ref.read(loginControllerProvider.notifier);
    final codeStep = state.phone != null;
    final error = state.error;

    return Scaffold(
      // Transparent: no hard edge over the glow.
      appBar: AppBar(backgroundColor: Colors.transparent, scrolledUnderElevation: 0),
      extendBodyBehindAppBar: true,
      body: Stack(
        children: [
          const PositionedDirectional(top: -140, end: -120, child: AccentGlow(size: 420, opacity: 0.18)),
          SafeArea(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, 64, JomhorSpace.xl, JomhorSpace.xl),
              children: [
                // The Jomhor lockup and tagline (design/BRAND.md); the club appears in its own content only.
                Entrance(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      JomhorLockup(height: 56, entrance: true, busy: state.busy),
                      const SizedBox(height: JomhorSpace.s),
                      Text(l.brandTagline, style: JomhorType.body.copyWith(color: JomhorColors.textSecondary)),
                    ],
                  ),
                ),
                const SizedBox(height: JomhorSpace.xxl),
                Entrance(index: 1, child: Text(l.loginTitle, style: JomhorType.h1.copyWith(fontSize: 34))),
                const SizedBox(height: JomhorSpace.m),
                Entrance(
                  index: 2,
                  child: Text(
                    codeStep ? l.loginCodeIntro(l.ltr(state.phone!)) : l.loginPhoneIntro,
                    style: JomhorType.body.copyWith(color: JomhorColors.textSecondary, fontWeight: FontWeight.w400),
                  ),
                ),
                const SizedBox(height: JomhorSpace.xxl),
                if (!codeStep)
                  TextField(
                    key: const Key('login.phone'),
                    controller: _phone,
                    keyboardType: TextInputType.phone,
                    // A phone number reads left to right in every locale.
                    textDirection: TextDirection.ltr,
                    autofillHints: const [AutofillHints.telephoneNumber],
                    decoration: InputDecoration(
                      labelText: l.loginPhoneLabel,
                      hintText: l.loginPhoneHint,
                      prefixIcon: const Icon(Icons.phone_iphone_rounded),
                    ),
                    onSubmitted: state.busy ? null : controller.submitPhone,
                  )
                else
                  TextField(
                    key: const Key('login.code'),
                    controller: _code,
                    keyboardType: TextInputType.number,
                    autofillHints: const [AutofillHints.oneTimeCode],
                    style: JomhorType.number.copyWith(letterSpacing: 8),
                    decoration: InputDecoration(
                      labelText: l.loginCodeLabel,
                      prefixIcon: const Icon(Icons.sms_outlined),
                    ),
                    onSubmitted: state.busy ? null : controller.submitCode,
                  ),
                if (error != null) ...[
                  const SizedBox(height: JomhorSpace.m),
                  Text(
                    errorText(l, error),
                    key: const Key('login.error'),
                    style: JomhorType.bodySmall.copyWith(color: Theme.of(context).colorScheme.error),
                  ),
                ],
                const SizedBox(height: JomhorSpace.xxl),
                FilledButton(
                  onPressed: state.busy
                      ? null
                      : () => codeStep ? controller.submitCode(_code.text) : controller.submitPhone(_phone.text),
                  child: Text(codeStep ? l.loginVerify : l.loginSendCode),
                ),
                if (codeStep) ...[
                  const SizedBox(height: JomhorSpace.m),
                  TextButton(onPressed: state.busy ? null : controller.resend, child: Text(l.loginResend)),
                  TextButton(
                    onPressed: state.busy
                        ? null
                        : () {
                            _code.clear();
                            controller.changePhone();
                          },
                    child: Text(l.loginChangePhone),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}
