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
  'EVENT_NOT_ON_SALE' => l.errorNotOnSale,
  'SOLD_OUT' => l.errorSoldOut,
  'ORDER_EXPIRED' => l.errorOrderExpired,
  'ORDER_NOT_PAYABLE' => l.errorOrderNotPayable,
  ApiError.network => l.errorNetwork,
  missingOrganizer => l.errorMissingOrganizer,
  _ => l.errorGeneric,
};

/// [errorText], plus the codes whose copy reads `details` (EVENT_NOT_ON_SALE and METHOD_NOT_AVAILABLE reasons,
/// PER_ACCOUNT_LIMIT numbers).
String apiErrorText(AppLocalizations l, ApiError e) => switch ((e.code, e.details)) {
  ('EVENT_NOT_ON_SALE', {'reason': 'NOT_STARTED'}) => l.errorNotStarted,
  ('EVENT_NOT_ON_SALE', {'reason': 'CLOSED'}) => l.errorSaleClosed,
  ('PER_ACCOUNT_LIMIT', {'limit': final int limit, 'held': final int held}) => l.errorPerAccountLimit(limit, held),
  ('METHOD_NOT_AVAILABLE', {'reason': 'TOO_LATE'}) => l.errorCashTooLate,
  ('METHOD_NOT_AVAILABLE', {'reason': 'CASH_CAP'}) => l.errorCashCap,
  ('METHOD_NOT_AVAILABLE', _) => l.errorMethodNotConfigured,
  _ => errorText(l, e.code),
};
