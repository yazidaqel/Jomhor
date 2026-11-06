import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/features/auth/login_controller.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';

/// Fan-facing copy for an error code (docs/04-api.md error table + client codes). Never the server message.
String errorText(AppLocalizations l, String code) => switch (code) {
  phoneInvalid => l.errorPhoneInvalid,
  'OTP_INVALID' => l.errorOtpInvalid,
  'OTP_EXPIRED' => l.errorOtpExpired,
  'OTP_RATE_LIMITED' => l.errorOtpRateLimited,
  'VALIDATION_FAILED' => l.errorValidation,
  'NOT_FOUND' => l.errorNotFound,
  ApiError.network => l.errorNetwork,
  missingOrganizer => l.errorMissingOrganizer,
  _ => l.errorGeneric,
};
