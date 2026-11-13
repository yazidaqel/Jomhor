// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Jomhor';

  @override
  String get navHome => 'Home';

  @override
  String get navTickets => 'Tickets';

  @override
  String get navClub => 'Club';

  @override
  String get navProfile => 'Profile';

  @override
  String get back => 'Back';

  @override
  String get retry => 'Try again';

  @override
  String get loginTitle => 'Sign in';

  @override
  String get loginPhoneIntro => 'Enter your phone number. We\'ll text you a code.';

  @override
  String get loginPhoneLabel => 'Phone number';

  @override
  String get loginPhoneHint => '06 12 34 56 78';

  @override
  String get loginSendCode => 'Get the code';

  @override
  String loginCodeIntro(String phone) {
    return 'Enter the 6-digit code sent to $phone.';
  }

  @override
  String get loginCodeLabel => '6-digit code';

  @override
  String get loginVerify => 'Confirm';

  @override
  String get loginChangePhone => 'Change number';

  @override
  String get loginResend => 'Resend the code';

  @override
  String errorPhoneInvalid(String example) {
    return 'This doesn\'t look like a Moroccan number. Try $example, for example.';
  }

  @override
  String get errorOtpInvalid => 'Wrong code. Check it and try again.';

  @override
  String get errorOtpExpired => 'This code has expired. Ask for a new one.';

  @override
  String get errorOtpRateLimited => 'Too many requests for this number. Wait a little before trying again.';

  @override
  String get errorValidation => 'Check what you entered.';

  @override
  String get errorNotFound => 'Not found. It may have been removed.';

  @override
  String get errorNetwork => 'No connection. Check your network and try again.';

  @override
  String get errorGeneric => 'Something went wrong. Try again in a moment.';

  @override
  String get heroLabelMatch => 'Next match · Home';

  @override
  String get heroLabelEvent => 'Next event';

  @override
  String get versus => 'VS';

  @override
  String matchday(int n) {
    return 'Matchday $n';
  }

  @override
  String whenWhere(String date, String time, String venue) {
    return '$date · $time · $venue';
  }

  @override
  String dotPair(String first, String second) {
    return '$first · $second';
  }

  @override
  String get countdownDays => 'Days';

  @override
  String get countdownHours => 'Hours';

  @override
  String get countdownMinutes => 'Min';

  @override
  String ctaBuyFrom(String price) {
    return 'Get my tickets · from $price';
  }

  @override
  String get ctaBuy => 'Get my tickets';

  @override
  String saleOpensOn(String date, String time) {
    return 'On sale $date at $time';
  }

  @override
  String get saleOpening => 'Sale opening any moment';

  @override
  String get soldOut => 'Sold out';

  @override
  String get saleClosed => 'Sale over';

  @override
  String get calendar => 'Fixtures';

  @override
  String chipFrom(String price) {
    return 'from $price';
  }

  @override
  String get chipSoon => 'Soon';

  @override
  String get homeEmpty => 'No upcoming match for now. Check back soon.';

  @override
  String rulesOpen(int limit) {
    String _temp0 = intl.Intl.pluralLogic(limit, locale: localeName, other: '$limit tickets', one: '1 ticket');
    return 'On sale · $_temp0 per account · named tickets';
  }

  @override
  String rulesNotStarted(String date, String time, int limit) {
    String _temp0 = intl.Intl.pluralLogic(limit, locale: localeName, other: '$limit tickets', one: '1 ticket');
    return 'On sale $date at $time · $_temp0 per account · named tickets';
  }

  @override
  String get rulesSoldOut => 'Sold out · every seat is gone';

  @override
  String get rulesClosed => 'Sale over for this event';

  @override
  String get zonesTitle => 'Prices by zone';

  @override
  String get zoneSeated => 'Seated';

  @override
  String get zoneStanding => 'Standing';

  @override
  String zoneGates(int count, String codes) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: 'Gates $codes', one: 'Gate $codes');
    return '$_temp0';
  }

  @override
  String zoneRemaining(int count) {
    final intl.NumberFormat countNumberFormat = intl.NumberFormat.decimalPattern(localeName);
    final String countString = countNumberFormat.format(count);

    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: '$countString left', one: '1 left');
    return '$_temp0';
  }

  @override
  String get zoneLastSeats => 'Last seats';

  @override
  String get paymentHint => 'Pay by card. Ticket in the app and on WhatsApp.';

  @override
  String get footerFrom => 'From';

  @override
  String get chooseZone => 'Choose my zone';

  @override
  String zonesSubtitle(String event, String date) {
    return '$event · $date';
  }

  @override
  String get pickZoneHint => 'Tap a zone to pick your seats.';

  @override
  String get pickedZone => 'Your zone';

  @override
  String placesMax(int max) {
    return 'Tickets · max $max';
  }

  @override
  String get fewerPlaces => 'One ticket less';

  @override
  String get morePlaces => 'One more ticket';

  @override
  String get groupHint => 'Seats bought together are side by side. Each friend gets their own QR.';

  @override
  String get total => 'Total';

  @override
  String get continueCta => 'Continue';

  @override
  String get allSoldOut => 'Sold out · every zone is gone';

  @override
  String get payTitle => 'Payment';

  @override
  String holdLeft(String time) {
    return 'Seats held · $time';
  }

  @override
  String summaryLine(int qty, String zone) {
    return '$qty × $zone';
  }

  @override
  String get serviceFee => 'Service fee';

  @override
  String get holdersTitle => 'Named tickets';

  @override
  String get holderMe => 'Ticket 1 · Me';

  @override
  String holderN(int n) {
    return 'Ticket $n';
  }

  @override
  String get holderNameLabel => 'First and last name';

  @override
  String get holderNameError => 'First and last name in Latin or Arabic letters, not both, 2 to 60 characters.';

  @override
  String get holderPhoneLabel => 'Your friend\'s WhatsApp (optional)';

  @override
  String get holderPhoneHelp =>
      'They get their ticket on WhatsApp and in the app. Without a number, the ticket stays with you.';

  @override
  String holderPhoneError(String example) {
    return 'Invalid Moroccan number. Try $example.';
  }

  @override
  String get methodTitle => 'Payment method';

  @override
  String get methodCard => 'Bank card';

  @override
  String get methodCardHint => 'Visa, Mastercard · 3D Secure';

  @override
  String get instant => 'Instant';

  @override
  String payCta(String total) {
    return 'Pay $total';
  }

  @override
  String get payCaption => 'Tickets sent in the app and on WhatsApp';

  @override
  String get friendsConsent => 'I confirm these people agree to get their ticket on WhatsApp';

  @override
  String get friendsConsentMissing => 'Tick the box to confirm your friends agree.';

  @override
  String get checkPayment => 'Check the payment status';

  @override
  String busyRetry(int s) {
    return 'Lots of fans right now · trying again in $s s';
  }

  @override
  String get expiredTitle => 'Reservation expired';

  @override
  String get expiredBody => 'Your seats were released. Pick them again.';

  @override
  String get expiredCta => 'Pick again';

  @override
  String get cardPageTitle => 'Secure payment';

  @override
  String get methodWallet => 'Mobile wallet';

  @override
  String get methodWalletHint => 'Pay from your wallet';

  @override
  String get methodCash => 'Cash at an agency';

  @override
  String get methodCashHint => 'A code to pay at the club\'s ticket office or at an agency';

  @override
  String methodCashBefore(String deadline) {
    return 'Before $deadline';
  }

  @override
  String get methodCashTooLate => 'Too late for cash';

  @override
  String payWalletCta(String total) {
    return 'Pay $total by wallet';
  }

  @override
  String get payCashCta => 'Get my cash code';

  @override
  String get errorCashTooLate => 'Too late to pay cash: the sale closes soon. Choose another payment method.';

  @override
  String get errorCashCap => 'Cash payment isn\'t available for this event right now. Choose another payment method.';

  @override
  String get errorMethodNotConfigured => 'This payment method isn\'t available. Choose another one.';

  @override
  String get cashTitle => 'Your cash payment code';

  @override
  String cashHoldBanner(String deadline) {
    return 'Seats held · pay before $deadline';
  }

  @override
  String get cashCodeLabel => 'Code to show';

  @override
  String cashExactAmount(String amount) {
    return 'Exact amount to pay: $amount';
  }

  @override
  String get cashCopy => 'Copy the code';

  @override
  String get cashCopied => 'Code copied';

  @override
  String get cashTimeLeft => 'Time left';

  @override
  String get cashWhereTitle => 'Where to pay';

  @override
  String cashNetworks(String names) {
    return 'At an agency: $names';
  }

  @override
  String get cashStepsTitle => 'How it works';

  @override
  String get cashStep1Title => 'Go to the ticket office or an agency';

  @override
  String get cashStep2Title => 'Give the code and the exact amount';

  @override
  String get cashStep3Title => 'Your tickets arrive';

  @override
  String get cashStep3Body => 'In the app and on WhatsApp, as soon as the payment is recorded.';

  @override
  String get cashPayNowTitle => 'Rather pay now?';

  @override
  String get cashPayByCard => 'Pay by card';

  @override
  String get cashPayByWallet => 'Pay by wallet';

  @override
  String get cashHostedPending => 'A card or wallet payment is being confirmed. Your code stays valid meanwhile.';

  @override
  String get cashStatus => 'Waiting for payment · this page updates by itself.';

  @override
  String get cashExpiredTitle => 'Code expired · seats released';

  @override
  String get cashExpiredBody => 'The time to pay this code has passed. You can order again if seats are left.';

  @override
  String get backToEvent => 'Back to the event';

  @override
  String get orderPending => 'Payment being confirmed.';

  @override
  String get orderPendingHint => 'It takes a few seconds. Don\'t pay twice: the bank may confirm a little later.';

  @override
  String get orderPaymentFailed => 'Your payment didn\'t go through. You can try again while your seats are held.';

  @override
  String get orderAwaitingPayment => 'Order waiting for payment.';

  @override
  String get orderNotPaidYet => 'No payment has been made for this order yet. You can pay while your seats are held.';

  @override
  String get orderPayAgainCaution =>
      'Still no confirmation. Only try again if your card was declined and nothing was charged.';

  @override
  String get payAgain => 'Pay again';

  @override
  String get orderPaidTitle => 'You\'re in!';

  @override
  String get orderPaidBody => 'Your tickets are in the Tickets tab. Your friends get theirs on WhatsApp.';

  @override
  String get seeTickets => 'See my tickets';

  @override
  String get orderExpired => 'This reservation has expired.';

  @override
  String get backHome => 'Back to home';

  @override
  String get errorNotStarted => 'The sale hasn\'t started yet.';

  @override
  String get errorSaleClosed => 'The sale is over for this event.';

  @override
  String get errorNotOnSale => 'This event isn\'t on sale.';

  @override
  String get errorSoldOut => 'No seats left at this price. Choose another zone.';

  @override
  String errorPerAccountLimit(int limit, int held) {
    return 'Limit reached: $limit tickets per account for this event, you already have $held.';
  }

  @override
  String get errorOrderExpired => 'Your reservation expired. Pick your seats again.';

  @override
  String get errorOrderNotPayable => 'This order can no longer be paid.';

  @override
  String get close => 'Close';

  @override
  String get ticketsTitle => 'My tickets';

  @override
  String get ticketsUpcoming => 'Upcoming';

  @override
  String get ticketsPast => 'Past';

  @override
  String get ticketsEmpty => 'No tickets yet. Your tickets land here as soon as the payment is confirmed.';

  @override
  String get ticketsBrowse => 'See the matches';

  @override
  String get ticketsOffline => 'Offline · tickets saved on your phone';

  @override
  String ticketRowFacts(String zone, String ticketNo) {
    return '$zone · No. $ticketNo';
  }

  @override
  String get ticketMatchday => 'Matchday mode';

  @override
  String get ticketBrightness => 'Brightness at maximum';

  @override
  String get ticketZone => 'Zone';

  @override
  String ticketGates(int count) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: 'Gates', one: 'Gate');
    return '$_temp0';
  }

  @override
  String ticketGatesList(int count, String codes) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: 'Gates $codes', one: 'Gate $codes');
    return '$_temp0';
  }

  @override
  String get listSeparator => ', ';

  @override
  String get ticketKickoff => 'Kick-off';

  @override
  String get ticketStart => 'Start';

  @override
  String get ticketHolder => 'Holder';

  @override
  String get ticketNumber => 'Ticket no.';

  @override
  String get ticketSeat => 'Seat';

  @override
  String ticketNextCode(int s) {
    return 'New code in $s s';
  }

  @override
  String get ticketWorksOffline => 'Works without network';

  @override
  String ticketQrLabel(String ticketNo) {
    return 'QR code of ticket $ticketNo';
  }

  @override
  String get ticketClosed => 'Event over';

  @override
  String get ticketCancelled => 'Ticket cancelled';

  @override
  String get ticketRefunded => 'Ticket refunded';

  @override
  String get ticketBlocked => 'Ticket blocked. Contact the club.';

  @override
  String get ticketNotValid => 'Ticket not valid';

  @override
  String get ticketNotFound => 'This ticket isn\'t on your phone.';

  @override
  String get ticketWhatsApp => 'WhatsApp';

  @override
  String get ticketWhatsAppHint => 'Get this ticket\'s PDF again on WhatsApp';

  @override
  String get ticketWhatsAppSent => 'Ticket sent again on WhatsApp';

  @override
  String get ticketWhatsAppRateLimited => 'This ticket was already sent again 3 times today. Try again tomorrow.';

  @override
  String get ticketWhatsAppNotValid => 'This ticket is no longer valid: it can\'t be sent again.';

  @override
  String get ticketWhatsAppFailed => 'This ticket can\'t be sent again right now. Try again later.';

  @override
  String get homeCashTitle => 'Order waiting for payment';

  @override
  String homeCashDeadline(String deadline) {
    return 'Cash code to pay before $deadline';
  }

  @override
  String myTicketTitle(String what, String date) {
    return 'My ticket · $what, $date';
  }

  @override
  String myTicketStarted(String time) {
    return 'started at $time';
  }

  @override
  String myTicketWhen(int days, String time) {
    String _temp0 = intl.Intl.pluralLogic(
      days,
      locale: localeName,
      other: 'in $days days',
      one: 'tomorrow at $time',
      zero: 'today at $time',
    );
    return '$_temp0';
  }

  @override
  String get cancel => 'Cancel';

  @override
  String get save => 'Save';

  @override
  String get profileName => 'Name';

  @override
  String get profileNameEmpty => 'Add your first and last name';

  @override
  String get profileEditName => 'Edit the name';

  @override
  String get profilePhone => 'Phone';

  @override
  String get profileLanguage => 'Language';

  @override
  String get languageFr => 'Français';

  @override
  String get languageAr => 'العربية';

  @override
  String get languageEn => 'English';

  @override
  String get profileLogout => 'Sign out';

  @override
  String get profileLogoutAll => 'Sign out of all my devices';

  @override
  String get profileLogoutBody => 'Your tickets will be removed from this phone. Sign in again to get them back.';

  @override
  String get profileLogoutAllBody =>
      'Your account will be signed out on this phone and on all your other devices. Your tickets will be removed from this phone.';

  @override
  String get profileLogoutConfirm => 'Sign me out';

  @override
  String get profileLogoutAllFailed =>
      'This phone is signed out, but your other devices couldn\'t be (no network). Sign in again and retry.';

  @override
  String get loading => 'Loading';

  @override
  String homeGreeting(String name) {
    return 'Hi $name';
  }

  @override
  String get homeGreetingNoName => 'Hi';

  @override
  String get homeGreetingGuest => 'Welcome';

  @override
  String get homeEmptyTitle => 'Nothing scheduled';

  @override
  String get homeOpenProfile => 'My profile';

  @override
  String get ticketsEmptyTitle => 'No ticket yet';

  @override
  String get ticketOpen => 'Open the ticket';

  @override
  String get brandTagline => 'Jomhor. The 12th man.';

  @override
  String profileVersion(String version) {
    return 'Version $version';
  }

  @override
  String get transferAction => 'Transfer';

  @override
  String get transferEyebrow => 'Transfer your ticket';

  @override
  String get transferTitle => 'Who are you giving it to?';

  @override
  String transferUntil(String when) {
    return 'Possible until $when';
  }

  @override
  String get transferPhone => 'WhatsApp number';

  @override
  String get transferPhoneHint => 'The new ticket goes to this number, in the app and on WhatsApp.';

  @override
  String get transferName => 'Full name';

  @override
  String get transferNameHint => 'The new ticket will be in this name.';

  @override
  String get transferNext => 'Continue';

  @override
  String get transferConfirmTitle => 'Confirm the transfer';

  @override
  String get transferTo => 'To';

  @override
  String get transferTicket => 'Ticket';

  @override
  String transferWarning(String name) {
    return 'Your QR will stop working. $name gets a new ticket.';
  }

  @override
  String get transferFinal => 'A transfer is final: you will not be able to undo it.';

  @override
  String get transferEdit => 'Edit';

  @override
  String get transferHold => 'Hold to transfer';

  @override
  String get transferHoldHint => 'Keep your finger down until the bar is full.';

  @override
  String get transferPending => 'Transferring…';

  @override
  String transferDone(String name) {
    return '$name gets the ticket in the app and on WhatsApp. Your old QR no longer works.';
  }

  @override
  String get transferSelf => 'That is your own number: this ticket is already yours.';

  @override
  String get transferNotValid => 'This ticket is no longer valid: it cannot be transferred.';

  @override
  String get transferAdmitted => 'This ticket has already been through a gate: it can no longer be transferred.';

  @override
  String get transferUnknown =>
      'We could not confirm the transfer. Your tickets are reloading: check them before trying again.';

  @override
  String transferClosed(String lead) {
    return 'Transfers close $lead before the start';
  }

  @override
  String get transferUnavailable => 'This ticket cannot be transferred';

  @override
  String durationHours(int n) {
    return '$n h';
  }

  @override
  String durationMinutes(int n) {
    return '$n min';
  }

  @override
  String transferRow(String name, String date) {
    return 'Transferred to $name · $date';
  }

  @override
  String transferRowNoName(String date) {
    return 'Transferred · $date';
  }

  @override
  String get profileLostPhone => 'Lost your phone?';

  @override
  String get profileLostPhoneHint => 'A new QR for your upcoming tickets';

  @override
  String get lostPhoneBody =>
      'Your upcoming tickets get a new QR on this phone. The ones on the lost phone will no longer get in.';

  @override
  String get lostPhoneSignedOut =>
      'The lost phone will be signed out: it cannot receive your new tickets. This phone stays signed in.';

  @override
  String get lostPhoneNone => 'No upcoming ticket can get a new QR.';

  @override
  String get lostPhoneConfirm => 'New QRs';

  @override
  String lostPhoneDone(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'New QRs ready for $count tickets.',
      one: 'New QR ready for 1 ticket.',
    );
    return '$_temp0';
  }

  @override
  String get lostPhoneFailed => 'Some tickets did not get a new QR. Check your tickets.';

  @override
  String get myClubs => 'My clubs';

  @override
  String get chipAll => 'All';

  @override
  String get clubsTitle => 'Your clubs';

  @override
  String get clubsIntro => 'Follow your clubs to see their matches first. Change them any time.';

  @override
  String get clubsSearch => 'Search clubs';

  @override
  String get clubsNoResult => 'No club matches.';

  @override
  String get clubFollow => 'Follow';

  @override
  String get clubFollowing => 'Following';

  @override
  String get clubsContinue => 'Continue';

  @override
  String get clubsSeeAll => 'See all';

  @override
  String get clubsDone => 'Done';

  @override
  String clubKind(String kind) {
    String _temp0 = intl.Intl.selectLogic(kind, {
      'SPORTS_CLUB': 'Sports club',
      'PROMOTER': 'Promoter',
      'VENUE': 'Venue',
      'other': 'Organizer',
    });
    return '$_temp0';
  }

  @override
  String get homeNoMatch => 'No upcoming match';

  @override
  String get homeNoEvent => 'No upcoming event';

  @override
  String get homeSeeAll => 'See all';

  @override
  String get gateRoute => 'Directions';

  @override
  String get gateOpenMaps => 'Open in Maps';

  @override
  String get gateFlipBack => 'Turn the ticket over';

  @override
  String get gateOthers => 'Other gates for your zone';

  @override
  String get gateLevelLow => 'Quiet';

  @override
  String get gateLevelMedium => 'Moderate';

  @override
  String get gateLevelHigh => 'Busy';

  @override
  String gateSmoother(String code) {
    return 'Gate $code is quieter right now';
  }

  @override
  String gateShow(String gates) {
    return '$gates, see directions';
  }

  @override
  String gateSideLabel(String code) {
    return 'Gate side of the ticket, gate $code';
  }

  @override
  String get ticketEntered => 'You\'re in';

  @override
  String ticketEnteredAt(String time, String code) {
    return 'Entered at $time · Gate $code';
  }

  @override
  String gateMore(int count) {
    return '+$count';
  }

  @override
  String clubTabTitle(int count) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: 'Your clubs', one: 'Your club');
    return '$_temp0';
  }

  @override
  String get clubNoFollowsTitle => 'Pick your clubs';

  @override
  String get clubNoFollows => 'Follow a club to get your member card and your season at the stadium.';

  @override
  String get clubNoFollowsAction => 'Pick my clubs';

  @override
  String get clubMemberCard => 'Member card';

  @override
  String get clubMemberNo => 'Member no.';

  @override
  String clubMemberSince(String date) {
    return 'since $date';
  }

  @override
  String get clubJoin => 'Join the club · free';

  @override
  String get clubJoinPitch => 'Your member card, your season at the stadium and the club\'s news.';

  @override
  String clubJoinTitle(String club) {
    return 'Join $club';
  }

  @override
  String get clubJoinBody => 'It\'s free. You choose what the club may send you and change it whenever you like.';

  @override
  String get clubJoinConfirm => 'Join';

  @override
  String get clubPushTitle => 'Club notifications';

  @override
  String get clubPushBody => 'Matches, tickets and club news, on this phone.';

  @override
  String get clubWhatsappTitle => 'Club WhatsApp messages';

  @override
  String get clubWhatsappBody => 'Club news on your WhatsApp.';

  @override
  String get clubSeason => 'Your season';

  @override
  String clubSeasonNext(String date) {
    return 'Next · $date';
  }

  @override
  String clubSeasonLabel(int attended, int total) {
    return '$attended of $total';
  }

  @override
  String clubSeasonLabelNext(String title) {
    return 'next: $title';
  }

  @override
  String clubMatchesAttended(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count matches at the stadium',
      one: '1 match at the stadium',
      zero: 'No match at the stadium',
    );
    return '$_temp0';
  }

  @override
  String clubEventsAttended(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count events attended',
      one: '1 event attended',
      zero: 'No event attended',
    );
    return '$_temp0';
  }

  @override
  String clubToNextTier(int count, String tier) {
    return '$count more to $tier';
  }

  @override
  String get clubRecent => 'Latest';

  @override
  String get clubAttended => 'I was there';

  @override
  String get clubMissed => 'Missed';

  @override
  String get clubMessages => 'Club messages';

  @override
  String get clubLeave => 'Leave the club';

  @override
  String clubLeaveTitle(String club) {
    return 'Leave $club?';
  }

  @override
  String get clubLeaveBody => 'You won\'t get the club\'s messages any more. If you come back, you keep your number.';

  @override
  String get clubLeaveConfirm => 'Leave';

  @override
  String memberStripNo(String number) {
    return 'No. $number';
  }

  @override
  String memberStripMatches(int count) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: '$count matches', one: '1 match');
    return '$_temp0';
  }

  @override
  String memberStripEvents(int count) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: '$count events', one: '1 event');
    return '$_temp0';
  }

  @override
  String get splitPays => 'Pays for their seat';

  @override
  String splitYouPay(int count, String total) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'You pay $count seats · $total',
      one: 'You pay 1 seat · $total',
    );
    return '$_temp0';
  }

  @override
  String splitFriendsPay(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count seats paid by your friends',
      one: '1 seat paid by your friends',
    );
    return '$_temp0';
  }

  @override
  String get splitCaption => 'Your friends have 2 h after you pay';

  @override
  String get splitTooLate => 'Too late to split';

  @override
  String get holderOwnPhone => 'This number is yours';

  @override
  String holderLimit(String label) {
    return '$label has already reached their limit';
  }

  @override
  String get groupBand => 'Your crew';

  @override
  String groupPaid(int paid, int total) {
    return '$paid/$total paid';
  }

  @override
  String get groupPaidWord => 'paid';

  @override
  String groupDue(String time) {
    return 'To pay · before $time';
  }

  @override
  String get groupPaidChip => 'Paid';

  @override
  String get groupExpiredChip => 'Expired';

  @override
  String get groupRemind => 'Remind on WhatsApp';

  @override
  String get groupCopyLink => 'Copy link';

  @override
  String get groupLinkCopied => 'Link copied';

  @override
  String groupRemindText(String event, String time, String url) {
    return 'I kept you a seat for $event. Pay for it before $time:\n$url';
  }

  @override
  String memberKept(String label) {
    return '$label kept you a seat';
  }

  @override
  String get memberKeptAnon => 'A friend kept you a seat';

  @override
  String get memberExpired => 'The seat was released';

  @override
  String homeMemberTitle(String time) {
    return 'Your seat is waiting · before $time';
  }

  @override
  String get inboxTitle => 'Notifications';

  @override
  String get inboxBell => 'Notifications';

  @override
  String get inboxBellUnread => 'Notifications · new messages';

  @override
  String get inboxEmpty => 'Nothing yet';

  @override
  String get inboxJustNow => 'Just now';

  @override
  String inboxMinutesAgo(int count) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: '$count min ago', one: '1 min ago');
    return '$_temp0';
  }

  @override
  String inboxHoursAgo(int count) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: '$count h ago', one: '1 h ago');
    return '$_temp0';
  }

  @override
  String inboxDaysAgo(int count) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: '$count days ago', one: 'yesterday');
    return '$_temp0';
  }

  @override
  String get profilePushTitle => 'Notifications';

  @override
  String get profilePushBody => 'Tickets you receive, payments and news from your clubs';

  @override
  String get profilePushOn => 'On';

  @override
  String get profilePushDenied => 'Turn them on in your phone settings';

  @override
  String get profilePushEnable => 'Turn on';

  @override
  String queueOpensAt(String time) {
    return 'Waiting room from $time';
  }

  @override
  String get queueLoginNow => 'Log in now';

  @override
  String get queueJoin => 'Join the queue';

  @override
  String get queueResume => 'Back to my place';

  @override
  String get chipQueue => 'Waiting room';

  @override
  String get roomHighDemand => 'High-demand sale · waiting room';

  @override
  String roomEligibility(String rules) {
    return 'Verified fans only: $rules';
  }

  @override
  String roomVerifiedBefore(String date) {
    return 'account verified before $date';
  }

  @override
  String roomMemberBefore(String date) {
    return 'member since before $date';
  }

  @override
  String roomMinEvents(int n) {
    String _temp0 = intl.Intl.pluralLogic(
      n,
      locale: localeName,
      other: '$n matches at the stadium',
      one: '1 match at the stadium',
    );
    return '$_temp0';
  }

  @override
  String get queueTitle => 'Waiting room';

  @override
  String get queuePlaceKept => 'Your place is kept';

  @override
  String get queueJoining => 'Joining the queue…';

  @override
  String queueDrawAt(String time) {
    return 'Draw at $time';
  }

  @override
  String queueDrawText(String time) {
    return 'Everyone here before $time has the same chance: the order is drawn at random. After that, first come, first served.';
  }

  @override
  String queueInRoom(int n) {
    String _temp0 = intl.Intl.pluralLogic(
      n,
      locale: localeName,
      other: '$n fans in the queue',
      one: '1 fan in the queue',
    );
    return '$_temp0';
  }

  @override
  String get queueSeconds => 'Sec';

  @override
  String get queueRankingTitle => 'Placing you';

  @override
  String get queueRanking => 'Your position arrives in a few seconds.';

  @override
  String get queuePositionLabel => 'Your position';

  @override
  String queuePosition(int n) {
    return 'No. $n';
  }

  @override
  String queueAhead(int n) {
    String _temp0 = intl.Intl.pluralLogic(
      n,
      locale: localeName,
      other: '$n fans ahead of you',
      one: '1 fan ahead of you',
      zero: 'Nobody ahead of you',
    );
    return '$_temp0';
  }

  @override
  String queueWait(String time) {
    return 'Estimated wait · $time';
  }

  @override
  String get queueWaitLess => 'less than a minute';

  @override
  String queueWaitMin(int n) {
    return 'about $n min';
  }

  @override
  String get queueWaitHour => 'more than an hour';

  @override
  String get queuePaused => 'Admission paused · your place is kept';

  @override
  String get queueKeepPlace => 'You can close the app: your place is kept';

  @override
  String queueLimit(int n) {
    String _temp0 = intl.Intl.pluralLogic(n, locale: localeName, other: '$n tickets', one: '1 ticket');
    return 'Limit $_temp0 per account · named tickets';
  }

  @override
  String queueRemaining(int n) {
    String _temp0 = intl.Intl.pluralLogic(n, locale: localeName, other: '$n places left', one: '1 place left');
    return '$_temp0';
  }

  @override
  String get queueSoldOut => 'Everything is reserved for now · places may free up';

  @override
  String get queueVerified => 'Verified fan';

  @override
  String get queueTurnTitle => 'It\'s your turn';

  @override
  String queueTurnLeft(String time) {
    return 'You have $time to book';
  }

  @override
  String get queueChoose => 'Choose my seats';

  @override
  String get queueExpiredTitle => 'Your turn has passed';

  @override
  String get queueExpiredBody => 'A new place puts you at the back of the queue.';

  @override
  String get queueRejoin => 'Get a new place';

  @override
  String get queueOffline => 'Offline · your place is kept';

  @override
  String get queueProgress => 'Progress in the queue';

  @override
  String get queueNotEligibleTitle => 'Sale reserved for verified fans';

  @override
  String queueAccountTooNew(String date) {
    return 'Account verified after $date';
  }

  @override
  String queueNotMember(String date) {
    return 'Reserved for members who joined before $date';
  }

  @override
  String queueNotEnoughEvents(int n, int attended) {
    return 'You need $n matches at the stadium · you have $attended';
  }

  @override
  String get queueDeviceLimit => 'Too many accounts on this phone for this sale';

  @override
  String queueRateLimited(int s) {
    return 'Lots of fans on this network · trying again in $s s';
  }

  @override
  String get queueRateLimitedAgain => 'Lots of fans on this network. Try again in a moment.';

  @override
  String get queueNotOpenTitle => 'The queue is not open yet';

  @override
  String get queueNotOpen => 'Keep this screen open: you will join the queue automatically.';

  @override
  String get queueClosed => 'Sales are closed for this event.';

  @override
  String turnBar(String time) {
    return 'Your turn · $time';
  }
}
