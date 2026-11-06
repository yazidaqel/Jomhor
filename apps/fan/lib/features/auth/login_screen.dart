import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/features/auth/login_controller.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

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
      appBar: AppBar(backgroundColor: JomhorColors.ground),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(JomhorSpace.xl),
          children: [
            Text(l.loginTitle, style: JomhorType.h1),
            const SizedBox(height: JomhorSpace.m),
            Text(codeStep ? l.loginCodeIntro(state.phone!) : l.loginPhoneIntro),
            const SizedBox(height: JomhorSpace.xxl),
            if (!codeStep)
              TextField(
                key: const Key('login.phone'),
                controller: _phone,
                keyboardType: TextInputType.phone,
                autofillHints: const [AutofillHints.telephoneNumber],
                decoration: InputDecoration(labelText: l.loginPhoneLabel, hintText: l.loginPhoneHint),
                onSubmitted: state.busy ? null : controller.submitPhone,
              )
            else
              TextField(
                key: const Key('login.code'),
                controller: _code,
                keyboardType: TextInputType.number,
                autofillHints: const [AutofillHints.oneTimeCode],
                decoration: InputDecoration(labelText: l.loginCodeLabel),
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
    );
  }
}
