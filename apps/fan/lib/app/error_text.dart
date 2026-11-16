import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/auth/login_controller.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/ui/bidi.dart';

/// Fan-facing copy for an error code (docs/04-api.md error table + client codes). Never the server message.
String errorText(AppLocalizations l, String code) => switch (code) {
  phoneInvalid => l.errorPhoneInvalid(l.ltr(examplePhone)),
  'OTP_INVALID' => l.errorOtpInvalid,
  'OTP_EXPIRED' => l.errorOtpExpired,
  'OTP_RATE_LIMITED' => l.errorOtpRateLimited,
  'VALIDATION_FAILED' => l.errorValidation,
  'NOT_FOUND' => l.errorNotFound,
  'EVENT_NOT_ON_SALE' => l.errorNotOnSale,
  'SOLD_OUT' => l.errorSoldOut,
  'MEMBERS_ONLY' => l.holderMembersOnlyAny,
  'ORDER_EXPIRED' => l.errorOrderExpired,
  'ORDER_NOT_PAYABLE' => l.errorOrderNotPayable,
  'PRODUCT_NOT_ON_SALE' => l.errorProductNotOnSale,
  'MEMBERSHIP_PAID' => l.errorMembershipPaid,
  'PASS_STATUS' => l.errorPassStatus,
  ApiError.network => l.errorNetwork,
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
  ('MEMBERS_ONLY', {'paid_required': true}) => l.holderMembersOnlyPaidAny,
  ('VALIDATION_FAILED', {'fields': {'installments': _}}) => l.errorPlanNotOffered,
  _ => errorText(l, e.code),
};

/// Lot 5 step 3 (Q189): the refusal (`MEMBERS_ONLY`, `NOT_ELIGIBLE` `NOT_MEMBER`) needs a cotisation paid up.
bool needsDues(ApiError e) => e.details['paid_required'] == true;

/// Lot 5 step 2, said on a holder (screen 4's row, screen 8's phone): `MEMBERS_ONLY` with its date in the venue
/// [zone] ("à jour de cotisation" when [needsDues]), or `PER_ACCOUNT_LIMIT` reason `HOLDER`; null for anything else.
String? holderRefusalText(AppLocalizations l, ApiError e, String zone) => switch ((e.code, e.details)) {
  ('MEMBERS_ONLY', {'paid_required': true, 'member_joined_before': final String at})
      when DateTime.tryParse(at) != null =>
    l.holderMembersOnlyPaid(venueDay(DateTime.parse(at), zone, l.localeName)),
  ('MEMBERS_ONLY', {'paid_required': true}) => l.holderMembersOnlyPaidAny,
  ('MEMBERS_ONLY', {'member_joined_before': final String at}) when DateTime.tryParse(at) != null => l.holderMembersOnly(
    venueDay(DateTime.parse(at), zone, l.localeName),
  ),
  ('MEMBERS_ONLY', _) => l.holderMembersOnlyAny,
  ('PER_ACCOUNT_LIMIT', {'reason': 'HOLDER', 'limit': final int limit}) => l.holderCapReached(limit),
  _ => null,
};
