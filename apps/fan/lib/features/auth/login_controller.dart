import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/features/auth/auth_repository.dart';
import 'package:jomhor_fan/features/auth/phone.dart';

/// Client-side code: the number is not a Moroccan mobile/landline (no request sent).
const phoneInvalid = 'PHONE_INVALID';

class LoginState {
  const new({this.phone, this.busy = false, this.error});

  /// E.164, set once a code was requested: the screen shows the code step.
  final String? phone;
  final bool busy;

  /// An `ApiError.code` or [phoneInvalid].
  final String? error;
}

class LoginController extends Notifier<LoginState> {
  @override
  LoginState build() => const LoginState();

  Future<void> submitPhone(String input) async {
    final phone = normalizePhone(input);
    if (phone == null) {
      state = const LoginState(error: phoneInvalid);
      return;
    }
    await _run(phone, () => ref.read(authRepositoryProvider).requestCode(phone));
  }

  /// Six digits once Arabic-Indic digits and separators are stripped.
  static String? cleanCode(String input) {
    final digits = asciiDigits(input).replaceAll(RegExp(r'\D'), '');
    return digits.length == 6 ? digits : null;
  }

  Future<void> submitCode(String input) async {
    final phone = state.phone!;
    final code = cleanCode(input);
    if (code == null) {
      state = LoginState(phone: phone, error: 'OTP_INVALID');
      return;
    }
    await _run(phone, () async {
      final session = await ref.read(authRepositoryProvider).verify(phone, code);
      // The router leaves /login as soon as the session flips.
      await ref.read(sessionProvider.notifier).signIn(session.token);
    });
  }

  Future<void> resend() => _run(state.phone!, () => ref.read(authRepositoryProvider).requestCode(state.phone!));

  void changePhone() => state = const LoginState();

  Future<void> _run(String phone, Future<void> Function() call) async {
    final current = state.phone;
    state = LoginState(phone: current, busy: true);
    try {
      await call();
      if (ref.mounted) state = LoginState(phone: phone);
    } on ApiError catch (e) {
      if (ref.mounted) state = LoginState(phone: current, error: e.code);
    } on Object {
      // A bad response shape or a storage failure: generic copy, and the buttons work again.
      if (ref.mounted) state = LoginState(phone: current, error: ApiError.unexpectedResponse);
    }
  }
}

final NotifierProvider<LoginController, LoginState> loginControllerProvider =
    NotifierProvider.autoDispose<LoginController, LoginState>(LoginController.new);
