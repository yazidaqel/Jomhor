// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Arabic (`ar`).
class AppLocalizationsAr extends AppLocalizations {
  AppLocalizationsAr([String locale = 'ar']) : super(locale);

  @override
  String get appTitle => 'جمهور';

  @override
  String get navHome => 'الرئيسية';

  @override
  String get navTickets => 'تذاكري';

  @override
  String get navClub => 'النادي';

  @override
  String get navProfile => 'حسابي';

  @override
  String get back => 'رجوع';

  @override
  String get retry => 'عاود جرب';

  @override
  String get loginTitle => 'دخل لحسابك';

  @override
  String get loginPhoneIntro => 'دخل نمرة التيليفون ديالك. غادي نصيفطو ليك كود فـ SMS.';

  @override
  String get loginPhoneLabel => 'نمرة التيليفون';

  @override
  String get loginPhoneHint => '06 12 34 56 78';

  @override
  String get loginSendCode => 'صيفط ليا الكود';

  @override
  String loginCodeIntro(String phone) {
    return 'دخل الكود ديال 6 أرقام اللي وصلك على $phone.';
  }

  @override
  String get loginCodeLabel => 'الكود ديال 6 أرقام';

  @override
  String get loginVerify => 'أكد';

  @override
  String get loginChangePhone => 'بدل النمرة';

  @override
  String get loginResend => 'عاود صيفط الكود';

  @override
  String errorPhoneInvalid(String example) {
    return 'هاد النمرة ما بايناش مغربية. جرب مثلا $example.';
  }

  @override
  String get errorOtpInvalid => 'الكود غالط. شوفو مزيان وعاود جرب.';

  @override
  String get errorOtpExpired => 'هاد الكود سالا. طلب كود جديد.';

  @override
  String get errorOtpRateLimited => 'بزاف ديال الطلبات على هاد النمرة. تسنى شوية عاد عاود جرب.';

  @override
  String get errorValidation => 'راجع المعلومات اللي دخلتي.';

  @override
  String get errorNotFound => 'ما لقيناهش. يمكن تحيد.';

  @override
  String get errorNetwork => 'ما كاينش الأنترنيت. شوف الريزو ديالك وعاود جرب.';

  @override
  String get errorGeneric => 'وقع شي مشكل. عاود جرب من دابا شوية.';

  @override
  String get heroLabelMatch => 'الماتش الجاي · فدارنا';

  @override
  String get heroLabelEvent => 'الحدث الجاي';

  @override
  String get versus => 'ضد';

  @override
  String matchday(int n) {
    return 'الدورة $n';
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
  String get countdownDays => 'أيام';

  @override
  String get countdownHours => 'ساعات';

  @override
  String get countdownMinutes => 'دقايق';

  @override
  String ctaBuyFrom(String price) {
    return 'شري بلايصك · من $price';
  }

  @override
  String get ctaBuy => 'شري بلايصك';

  @override
  String saleOpensOn(String date, String time) {
    return 'البيع نهار $date مع $time';
  }

  @override
  String get saleOpening => 'البيع غادي يتحل دابا';

  @override
  String get soldOut => 'كومبلي';

  @override
  String get saleClosed => 'سالا البيع';

  @override
  String get calendar => 'البرنامج';

  @override
  String chipFrom(String price) {
    return 'من $price';
  }

  @override
  String get chipSoon => 'قريبا';

  @override
  String get homeEmpty => 'ما كاين حتى ماتش دابا. رجع من بعد.';

  @override
  String rulesOpen(int limit) {
    String _temp0 = intl.Intl.pluralLogic(
      limit,
      locale: localeName,
      other: '$limit تذكرة',
      few: '$limit تذاكر',
      two: 'جوج تذاكر',
      one: 'تذكرة وحدة',
    );
    return 'البيع محلول · $_temp0 لكل حساب · التذاكر بالسمية';
  }

  @override
  String rulesNotStarted(String date, String time, int limit) {
    String _temp0 = intl.Intl.pluralLogic(
      limit,
      locale: localeName,
      other: '$limit تذكرة',
      few: '$limit تذاكر',
      two: 'جوج تذاكر',
      one: 'تذكرة وحدة',
    );
    return 'البيع نهار $date مع $time · $_temp0 لكل حساب · التذاكر بالسمية';
  }

  @override
  String get rulesSoldOut => 'كومبلي · تباعو كاع البلايص';

  @override
  String get rulesClosed => 'سالا البيع ديال هاد الحدث';

  @override
  String get zonesTitle => 'الأثمنة حسب المنطقة';

  @override
  String get zoneSeated => 'بلايص بالكراسي';

  @override
  String get zoneStanding => 'واقفين';

  @override
  String zoneGates(int count, String codes) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: 'البيبان $codes', one: 'الباب $codes');
    return '$_temp0';
  }

  @override
  String zoneRemaining(int count) {
    final intl.NumberFormat countNumberFormat = intl.NumberFormat.decimalPattern(localeName);
    final String countString = countNumberFormat.format(count);

    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$countString بلاصة باقية',
      few: '$countString بلايص باقيين',
      two: 'جوج بلايص باقيين',
      one: 'بلاصة وحدة باقية',
    );
    return '$_temp0';
  }

  @override
  String get zoneLastSeats => 'آخر البلايص';

  @override
  String get paymentHint => 'الخلاص بالكارط البنكية. التذكرة كتوصلك فالتطبيق وفواتساب.';

  @override
  String get footerFrom => 'ابتداء من';

  @override
  String get chooseZone => 'ختار المنطقة';

  @override
  String zonesSubtitle(String event, String date) {
    return '$event · $date';
  }

  @override
  String get pickZoneHint => 'ورك على شي منطقة باش تختار بلايصك.';

  @override
  String get pickedZone => 'المنطقة اللي ختاريتي';

  @override
  String placesMax(int max) {
    return 'التذاكر · $max على الأكثر';
  }

  @override
  String get fewerPlaces => 'نقص تذكرة';

  @override
  String get morePlaces => 'زيد تذكرة';

  @override
  String get groupHint => 'البلايص اللي كتشريهم مرة وحدة كيكونو حدا بعضياتهم. كل صاحب كيوصلو QR ديالو.';

  @override
  String get total => 'المجموع';

  @override
  String get continueCta => 'كمل';

  @override
  String get allSoldOut => 'كومبلي · تباعو كاع المناطق';

  @override
  String get payTitle => 'الخلاص';

  @override
  String holdLeft(String time) {
    return 'البلايص محجوزين ليك · $time';
  }

  @override
  String summaryLine(int qty, String zone) {
    return '$qty × $zone';
  }

  @override
  String get serviceFee => 'مصاريف الخدمة';

  @override
  String get holdersTitle => 'التذاكر بالسمية';

  @override
  String get holderMe => 'التذكرة 1 · أنا';

  @override
  String holderN(int n) {
    return 'التذكرة $n';
  }

  @override
  String get holderNameLabel => 'السمية والكنية';

  @override
  String get holderNameError => 'السمية والكنية بالحروف اللاتينية ولا العربية، ماشي بجوج، من 2 حتى 60 حرف.';

  @override
  String get holderPhoneLabel => 'الواتساب ديال صاحبك (إلا بغيتي)';

  @override
  String get holderPhoneHelp => 'كتوصلو التذكرة ديالو فواتساب وفالتطبيق. بلا نمرة، التذكرة كتبقى عندك.';

  @override
  String holderPhoneError(String example) {
    return 'النمرة المغربية ماشي صحيحة. جرب $example.';
  }

  @override
  String get methodTitle => 'طريقة الخلاص';

  @override
  String get methodCard => 'كارط بنكية';

  @override
  String get methodCardHint => 'Visa، Mastercard · 3D Secure';

  @override
  String get instant => 'فالحين';

  @override
  String payCta(String total) {
    return 'خلص $total';
  }

  @override
  String get payCaption => 'التذاكر كتوصلك فالتطبيق وفواتساب';

  @override
  String get friendsConsent => 'كنأكد بلي هاد الناس موافقين توصلهم التذكرة ديالهم فواتساب';

  @override
  String get friendsConsentMissing => 'علم على الخانة باش تأكد بلي صحابك موافقين.';

  @override
  String get checkPayment => 'شوف فين وصل الخلاص';

  @override
  String busyRetry(int s) {
    return 'كاين الضغط دابا · غادي نعاودو نجربو من بعد $s ثانية';
  }

  @override
  String get expiredTitle => 'سالا الحجز';

  @override
  String get expiredBody => 'البلايص ديالك تطلقو. ختارهم من جديد.';

  @override
  String get expiredCta => 'ختار من جديد';

  @override
  String get cardPageTitle => 'خلاص آمن';

  @override
  String get methodWallet => 'المحفظة الإلكترونية';

  @override
  String get methodWalletHint => 'خلص من المحفظة ديالك';

  @override
  String get methodCash => 'كاش فالوكالة';

  @override
  String get methodCashHint => 'كود تخلصو فالشباك ديال الفريق ولا فشي وكالة';

  @override
  String methodCashBefore(String deadline) {
    return 'قبل $deadline';
  }

  @override
  String get methodCashTooLate => 'فات الوقت ديال الكاش';

  @override
  String payWalletCta(String total) {
    return 'خلص $total بالمحفظة';
  }

  @override
  String get payCashCta => 'عطيني كود الكاش';

  @override
  String get errorCashTooLate => 'فات الوقت باش تخلص كاش: البيع قريب يتسد. ختار طريقة خلاص أخرى.';

  @override
  String get errorCashCap => 'الخلاص كاش ما بقاش ممكن لهاد الحدث دابا. ختار طريقة خلاص أخرى.';

  @override
  String get errorMethodNotConfigured => 'طريقة الخلاص هادي ما متوفراش. ختار وحدة أخرى.';

  @override
  String get cashTitle => 'الكود ديالك باش تخلص كاش';

  @override
  String cashHoldBanner(String deadline) {
    return 'البلايص محجوزين · خلص قبل $deadline';
  }

  @override
  String get cashCodeLabel => 'الكود اللي غادي توري';

  @override
  String cashExactAmount(String amount) {
    return 'المبلغ بالضبط: $amount';
  }

  @override
  String get cashCopy => 'كوبي الكود';

  @override
  String get cashCopied => 'الكود تكوبا';

  @override
  String get cashTimeLeft => 'الوقت اللي بقا';

  @override
  String get cashWhereTitle => 'فين تخلص';

  @override
  String cashNetworks(String names) {
    return 'فالوكالات: $names';
  }

  @override
  String get cashStepsTitle => 'كيفاش كيدوز';

  @override
  String get cashStep1Title => 'سير للشباك ولا لشي وكالة';

  @override
  String get cashStep2Title => 'عطيهم الكود والمبلغ بالضبط';

  @override
  String get cashStep3Title => 'كيوصلوك التذاكر';

  @override
  String get cashStep3Body => 'فالتطبيق وفواتساب، غير يتسجل الخلاص.';

  @override
  String get cashPayNowTitle => 'بغيتي تخلص دابا؟';

  @override
  String get cashPayByCard => 'خلص بالكارط';

  @override
  String get cashPayByWallet => 'خلص بالمحفظة';

  @override
  String get cashHostedPending => 'كاين خلاص بالكارط ولا بالمحفظة كيتأكد دابا. الكود ديالك باقي صالح حتى يسالي.';

  @override
  String get cashStatus => 'كنتسناو الخلاص · هاد الصفحة كتتجدد بوحدها.';

  @override
  String get cashExpiredTitle => 'سالا الكود · البلايص تطلقو';

  @override
  String get cashExpiredBody => 'فات الوقت باش تخلص هاد الكود. تقدر تعاود تطلب إلا بقاو البلايص.';

  @override
  String get backToEvent => 'رجع للحدث';

  @override
  String get orderPending => 'الخلاص كيتأكد دابا.';

  @override
  String get orderPendingHint => 'كياخد شي ثواني. ما تخلصش جوج مرات: البنك يقدر يأكد من بعد شوية.';

  @override
  String get orderPaymentFailed => 'الخلاص ديالك ما دازش. تقدر تعاود مادام البلايص محجوزين ليك.';

  @override
  String get orderAwaitingPayment => 'الطلب كيتسنى الخلاص.';

  @override
  String get orderNotPaidYet => 'باقي ما تخلص والو على هاد الطلب. تقدر تخلص مادام البلايص محجوزين ليك.';

  @override
  String get orderPayAgainCaution => 'باقي ما جا حتى تأكيد. عاود غير إلا الكارط ديالك ترفضات وما تقطع ليك والو.';

  @override
  String get payAgain => 'خلص مرة أخرى';

  @override
  String get orderPaidTitle => 'صافي، التذاكر واجدين!';

  @override
  String get orderPaidBody => 'تذاكرك كاينين فـ «تذاكري». صحابك كيوصلهم ديالهم فواتساب.';

  @override
  String get seeTickets => 'شوف تذاكري';

  @override
  String get orderExpired => 'هاد الحجز سالا.';

  @override
  String get backHome => 'رجع للرئيسية';

  @override
  String get errorNotStarted => 'البيع باقي ما بدا.';

  @override
  String get errorSaleClosed => 'سالا البيع ديال هاد الحدث.';

  @override
  String get errorNotOnSale => 'هاد الحدث ماشي للبيع.';

  @override
  String get errorSoldOut => 'ما بقاو بلايص فهاد الثمن. ختار منطقة أخرى.';

  @override
  String errorPerAccountLimit(int limit, int held) {
    return 'وصلتي للحد: $limit تذاكر لكل حساب فهاد الحدث، وعندك ديجا $held.';
  }

  @override
  String get errorOrderExpired => 'الحجز ديالك سالا. ختار البلايص من جديد.';

  @override
  String get errorOrderNotPayable => 'هاد الطلب ما بقاش ممكن يتخلص.';

  @override
  String get close => 'سد';

  @override
  String get ticketsTitle => 'تذاكري';

  @override
  String get ticketsUpcoming => 'الجايين';

  @override
  String get ticketsPast => 'اللي فاتو';

  @override
  String get ticketsEmpty => 'باقي ما عندك حتى تذكرة. تذاكرك كيوصلو هنا غير يتأكد الخلاص.';

  @override
  String get ticketsBrowse => 'شوف الماتشات';

  @override
  String get ticketsOffline => 'بلا أنترنيت · التذاكر محفوظين فالتيليفون ديالك';

  @override
  String ticketRowFacts(String zone, String ticketNo) {
    return '$zone · رقم $ticketNo';
  }

  @override
  String get ticketMatchday => 'نهار الماتش';

  @override
  String get ticketBrightness => 'الضو على آخرو';

  @override
  String get ticketZone => 'المنطقة';

  @override
  String ticketGates(int count) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: 'البيبان', one: 'الباب');
    return '$_temp0';
  }

  @override
  String ticketGatesList(int count, String codes) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: 'البيبان $codes', one: 'الباب $codes');
    return '$_temp0';
  }

  @override
  String get listSeparator => '، ';

  @override
  String get ticketKickoff => 'انطلاق الماتش';

  @override
  String get ticketStart => 'البداية';

  @override
  String get ticketHolder => 'مولا التذكرة';

  @override
  String get ticketNumber => 'رقم التذكرة';

  @override
  String get ticketSeat => 'البلاصة';

  @override
  String ticketNextCode(int s) {
    return 'كود جديد من بعد $s ثانية';
  }

  @override
  String get ticketWorksOffline => 'خدام بلا أنترنيت';

  @override
  String ticketQrLabel(String ticketNo) {
    return 'كود QR ديال التذكرة $ticketNo';
  }

  @override
  String get ticketClosed => 'سالا الحدث';

  @override
  String get ticketCancelled => 'التذكرة تلغات';

  @override
  String get ticketRefunded => 'التذكرة ترجعو فلوسها';

  @override
  String get ticketBlocked => 'التذكرة مبلوكية. تواصل مع الفريق.';

  @override
  String get ticketNotValid => 'التذكرة ماشي صالحة';

  @override
  String get ticketNotFound => 'هاد التذكرة ماشي فالتيليفون ديالك.';

  @override
  String get ticketWhatsApp => 'واتساب';

  @override
  String get ticketWhatsAppHint => 'توصل من جديد بالـ PDF ديال هاد التذكرة فواتساب';

  @override
  String get ticketWhatsAppSent => 'التذكرة تصيفطات من جديد فواتساب';

  @override
  String get ticketWhatsAppRateLimited => 'هاد التذكرة تصيفطات ديجا 3 مرات اليوم. عاود غدا.';

  @override
  String get ticketWhatsAppNotValid => 'هاد التذكرة ما بقاتش صالحة: ما يمكنش نعاودو نصيفطوها.';

  @override
  String get ticketWhatsAppFailed => 'ما قدرناش نصيفطو هاد التذكرة دابا. عاود من بعد.';

  @override
  String get homeCashTitle => 'طلب كيتسنى الخلاص';

  @override
  String homeCashDeadline(String deadline) {
    return 'كود الكاش خصو يتخلص قبل $deadline';
  }

  @override
  String myTicketTitle(String what, String date) {
    return 'تذكرتي · $what، $date';
  }

  @override
  String myTicketStarted(String time) {
    return 'بدا مع $time';
  }

  @override
  String myTicketWhen(int days, String time) {
    String _temp0 = intl.Intl.pluralLogic(
      days,
      locale: localeName,
      other: 'من بعد $days يوم',
      few: 'من بعد $days أيام',
      two: 'من بعد يومين',
      one: 'غدا مع $time',
      zero: 'اليوم مع $time',
    );
    return '$_temp0';
  }

  @override
  String get cancel => 'إلغاء';

  @override
  String get save => 'سجل';

  @override
  String get profileName => 'السمية';

  @override
  String get profileNameEmpty => 'زيد السمية والكنية ديالك';

  @override
  String get profileEditName => 'بدل السمية';

  @override
  String get profilePhone => 'التيليفون';

  @override
  String get profileLanguage => 'اللغة';

  @override
  String get languageFr => 'Français';

  @override
  String get languageAr => 'العربية';

  @override
  String get languageEn => 'English';

  @override
  String get profileLogout => 'خرج من الحساب';

  @override
  String get profileLogoutAll => 'خرج من كاع الأجهزة ديالي';

  @override
  String get profileLogoutBody => 'التذاكر ديالك غادي يتحيدو من هاد التيليفون. دخل مرة أخرى باش تلقاهم.';

  @override
  String get profileLogoutAllBody =>
      'الحساب ديالك غادي يخرج من هاد التيليفون ومن كاع الأجهزة الأخرين. التذاكر غادي يتحيدو من هاد التيليفون.';

  @override
  String get profileLogoutConfirm => 'خرجني';

  @override
  String get profileLogoutAllFailed =>
      'هاد التيليفون خرج، ولكن الأجهزة الأخرين ما قدروش (ما كاينش الأنترنيت). دخل من جديد وعاود جرب.';

  @override
  String get loading => 'كيتحمل';

  @override
  String homeGreeting(String name) {
    return 'أهلا $name';
  }

  @override
  String get homeGreetingNoName => 'أهلا';

  @override
  String get homeGreetingGuest => 'مرحبا بيك';

  @override
  String get homeEmptyTitle => 'ما كاين والو دابا';

  @override
  String get homeOpenProfile => 'حسابي';

  @override
  String get ticketsEmptyTitle => 'باقي ما عندك تذكرة';

  @override
  String get ticketOpen => 'حل التذكرة';

  @override
  String get brandTagline => 'جمهور. الرجل الثاني عشر.';

  @override
  String profileVersion(String version) {
    return 'النسخة $version';
  }

  @override
  String get transferAction => 'حول';

  @override
  String get transferEyebrow => 'حول التذكرة ديالك';

  @override
  String get transferTitle => 'لمن غادي تعطيها؟';

  @override
  String transferUntil(String when) {
    return 'ممكن حتى $when';
  }

  @override
  String get transferPhone => 'نمرة الواتساب';

  @override
  String get transferPhoneHint => 'التذكرة الجديدة غادي توصل لهاد النمرة، فالتطبيق وفالواتساب.';

  @override
  String get transferName => 'السمية الكاملة';

  @override
  String get transferNameHint => 'التذكرة الجديدة غادي تكون بهاد السمية.';

  @override
  String get transferNext => 'كمل';

  @override
  String get transferConfirmTitle => 'أكد التحويل';

  @override
  String get transferTo => 'ل';

  @override
  String get transferTicket => 'التذكرة';

  @override
  String transferWarning(String name) {
    return 'رمز QR ديالك ما غاديش يبقى يخدم. $name غادي ياخد تذكرة جديدة.';
  }

  @override
  String get transferFinal => 'التحويل نهائي: ما غاديش تقدر ترجع فيه.';

  @override
  String get transferEdit => 'بدل';

  @override
  String get transferHold => 'ضغط وخلي باش تحول';

  @override
  String get transferHoldHint => 'خلي صبعك مضغوط حتى تعمر الخانة.';

  @override
  String get transferPending => 'كنحولو…';

  @override
  String transferDone(String name) {
    return '$name غادي يلقى التذكرة فالتطبيق وفالواتساب. رمز QR القديم ديالك ما بقاش خدام.';
  }

  @override
  String get transferSelf => 'هادي النمرة ديالك: التذكرة ديجا عندك.';

  @override
  String get transferNotValid => 'هاد التذكرة ما بقاتش صالحة: ما يمكنش تتحول.';

  @override
  String get transferAdmitted => 'هاد التذكرة ديجا دازت من الباب: ما بقاتش تقدر تتحول.';

  @override
  String get transferUnknown => 'ما قدرناش نتأكدو من التحويل. التذاكر ديالك كيتعاودو يتحملو، شوفهم قبل ما تعاود.';

  @override
  String transferClosed(String lead) {
    return 'التحويل كيتسد $lead قبل البداية';
  }

  @override
  String get transferUnavailable => 'هاد التذكرة ما كتقدرش تحولها';

  @override
  String durationHours(int n) {
    return '$n س';
  }

  @override
  String durationMinutes(int n) {
    return '$n د';
  }

  @override
  String transferRow(String name, String date) {
    return 'تحولات ل $name · $date';
  }

  @override
  String transferRowNoName(String date) {
    return 'تحولات · $date';
  }

  @override
  String get profileLostPhone => 'ضاع ليك التيليفون؟';

  @override
  String get profileLostPhoneHint => 'QR جديد للتذاكر الجاية ديالك';

  @override
  String get lostPhoneBody =>
      'التذاكر الجاية ديالك غادي ياخدو QR جديد فهاد التيليفون. ديال التيليفون اللي ضاع ما غاديش يبقاو يدوزو.';

  @override
  String get lostPhoneSignedOut =>
      'التيليفون اللي ضاع غادي يتخرج من الحساب: ما غاديش يوصلو التذاكر الجداد ديالك. هاد التيليفون غادي يبقى داخل.';

  @override
  String get lostPhoneNone => 'حتى تذكرة جاية ما تقدر تاخد QR جديد.';

  @override
  String get lostPhoneConfirm => 'QR جداد';

  @override
  String lostPhoneDone(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'QR جداد واجدين ل $count تذكرة.',
      few: 'QR جداد واجدين ل $count تذاكر.',
      two: 'QR جداد واجدين لجوج تذاكر.',
      one: 'QR جديد واجد لتذكرة وحدة.',
    );
    return '$_temp0';
  }

  @override
  String get lostPhoneFailed => 'شي تذاكر ما خداوش QR جديد. شوف التذاكر ديالك.';

  @override
  String get myClubs => 'فرقي';

  @override
  String get chipAll => 'كلشي';

  @override
  String get clubsTitle => 'فرقك';

  @override
  String get clubsIntro => 'تابع فرقك باش تشوف الماتشات ديالهم هوما الأولين. تقدر تبدل وقتما بغيتي.';

  @override
  String get clubsSearch => 'قلّب على فريق';

  @override
  String get clubsNoResult => 'ما لقينا حتى فريق.';

  @override
  String get clubFollow => 'تابع';

  @override
  String get clubFollowing => 'متابَع';

  @override
  String get clubsContinue => 'كمّل';

  @override
  String get clubsSeeAll => 'شوف كلشي';

  @override
  String get clubsDone => 'ساليت';

  @override
  String clubKind(String kind) {
    String _temp0 = intl.Intl.selectLogic(kind, {
      'SPORTS_CLUB': 'فريق رياضي',
      'PROMOTER': 'منظم',
      'VENUE': 'قاعة',
      'other': 'منظم',
    });
    return '$_temp0';
  }

  @override
  String get homeNoMatch => 'ما كاين حتى ماتش جاي';

  @override
  String get homeNoEvent => 'ما كاين حتى حدث جاي';

  @override
  String get homeSeeAll => 'شوف كلشي';

  @override
  String get gateRoute => 'الطريق';

  @override
  String get gateOpenMaps => 'حل ف الخريطة';

  @override
  String get gateFlipBack => 'قلب التذكرة';

  @override
  String get gateOthers => 'بيبان خرين ديال المنطقة ديالك';

  @override
  String get gateLevelLow => 'سالكة';

  @override
  String get gateLevelMedium => 'متوسطة';

  @override
  String get gateLevelHigh => 'عامرة';

  @override
  String gateSmoother(String code) {
    return 'الباب $code سالك كثر دابا';
  }

  @override
  String gateShow(String gates) {
    return '$gates، شوف الطريق';
  }

  @override
  String gateSideLabel(String code) {
    return 'جيهة الباب ديال التذكرة، الباب $code';
  }

  @override
  String get ticketEntered => 'راك داخل';

  @override
  String ticketEnteredAt(String time, String code) {
    return 'دخلتي ف $time · الباب $code';
  }

  @override
  String gateMore(int count) {
    return '+$count';
  }

  @override
  String clubTabTitle(int count) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: 'فرقك', one: 'فريقك');
    return '$_temp0';
  }

  @override
  String get clubNoFollowsTitle => 'ختار فرقك';

  @override
  String get clubNoFollows => 'تابع شي فريق باش تاخد الكارطة ديال المنخرط والموسم ديالك فالملعب.';

  @override
  String get clubNoFollowsAction => 'ختار فرقي';

  @override
  String get clubMemberCard => 'كارطة المنخرط';

  @override
  String get clubMemberNo => 'رقم المنخرط';

  @override
  String clubMemberSince(String date) {
    return 'من $date';
  }

  @override
  String get clubJoin => 'نخرط فالنادي · فابور';

  @override
  String get clubJoinPitch => 'الكارطة ديالك، الموسم ديالك فالملعب والأخبار ديال النادي.';

  @override
  String clubJoinTitle(String club) {
    return 'نخرط ف$club';
  }

  @override
  String get clubJoinBody => 'فابور. نتا لي كتختار شنو يقدر يصيفط ليك النادي، وتقدر تبدل وقتما بغيتي.';

  @override
  String get clubJoinConfirm => 'نخرط';

  @override
  String get clubPushTitle => 'إشعارات النادي';

  @override
  String get clubPushBody => 'الماتشات، التذاكر والأخبار ديال النادي، فهاد التيليفون.';

  @override
  String get clubWhatsappTitle => 'ميساجات الواتساب ديال النادي';

  @override
  String get clubWhatsappBody => 'الأخبار ديال النادي فالواتساب ديالك.';

  @override
  String get clubSeason => 'الموسم ديالك';

  @override
  String clubSeasonNext(String date) {
    return 'الجاي · $date';
  }

  @override
  String clubSeasonLabel(int attended, int total) {
    return '$attended من $total';
  }

  @override
  String clubSeasonLabelNext(String title) {
    return 'الجاي: $title';
  }

  @override
  String clubMatchesAttended(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count ماتش فالملعب',
      few: '$count ماتشات فالملعب',
      two: 'جوج ماتشات فالملعب',
      one: 'ماتش واحد فالملعب',
      zero: 'حتى ماتش فالملعب',
    );
    return '$_temp0';
  }

  @override
  String clubEventsAttended(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'حضرتي $count حدث',
      few: 'حضرتي $count أحداث',
      two: 'حضرتي جوج أحداث',
      one: 'حضرتي حدث واحد',
      zero: 'حتى حدث ما حضرتي',
    );
    return '$_temp0';
  }

  @override
  String clubToNextTier(int count, String tier) {
    return 'باقي $count باش تولّي $tier';
  }

  @override
  String get clubRecent => 'الأخيرين';

  @override
  String get clubAttended => 'كنت تما';

  @override
  String get clubMissed => 'فاتني';

  @override
  String get clubMessages => 'الميساجات ديال النادي';

  @override
  String get clubLeave => 'خرج من النادي';

  @override
  String clubLeaveTitle(String club) {
    return 'تخرج من $club؟';
  }

  @override
  String get clubLeaveBody => 'ما بقاوش يوصلوك الميساجات ديال النادي. إلا رجعتي، كتلقى نفس الرقم ديالك.';

  @override
  String get clubLeaveConfirm => 'خرج';

  @override
  String memberStripNo(String number) {
    return 'رقم $number';
  }

  @override
  String memberStripMatches(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count ماتش',
      few: '$count ماتشات',
      two: 'جوج ماتشات',
      one: 'ماتش واحد',
      zero: '0 ماتش',
    );
    return '$_temp0';
  }

  @override
  String memberStripEvents(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count حدث',
      few: '$count أحداث',
      two: 'جوج أحداث',
      one: 'حدث واحد',
      zero: '0 حدث',
    );
    return '$_temp0';
  }
}
