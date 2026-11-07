// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Arabic (`ar`).
class AppLocalizationsAr extends AppLocalizations {
  AppLocalizationsAr([String locale = 'ar']) : super(locale);

  @override
  String get appTitle => 'Jomhor Porte';

  @override
  String get deviceNotEnrolled => 'الجهاز غير مسجل';

  @override
  String get deviceNotEnrolledHint => 'Scannez le QR d\'enrôlement affiché dans le back-office, onglet Appareils.';

  @override
  String get devicePending => 'في انتظار الموافقة';

  @override
  String get deviceActive => 'الجهاز مفعل';

  @override
  String get scanInactive => 'وضع الحدث غير مفعل';

  @override
  String get scanInactiveHint => 'حمّل بيان الحدث وافتح الجهاز برمزك السري.';

  @override
  String get scanModeIn => 'دخول';

  @override
  String scanTopLine(String code, String name, String mode) {
    return 'البوابة $code · $name · $mode';
  }

  @override
  String scanSyncOnline(int count) {
    return 'متصل · $count للمزامنة';
  }

  @override
  String scanSyncOffline(int count) {
    return 'غير متصل · $count للمزامنة';
  }

  @override
  String get scanHint => 'اعرض رمز QR الخاص بالتذكرة';

  @override
  String get scanCameraError => 'الكاميرا غير متاحة. استعمل زر المسح في الجهاز.';

  @override
  String get scanEntered => 'دخلوا هنا';

  @override
  String get scanRefusedCount => 'مرفوضة';

  @override
  String get scanRate => 'الوتيرة';

  @override
  String scanRateValue(int count) {
    return '$count / د';
  }

  @override
  String get scanNext => 'مسح التالي';

  @override
  String get scanFailed => 'خطأ';

  @override
  String get scanFailedReason => 'لم يُسجَّل المسح. امسح التذكرة مرة أخرى.';

  @override
  String scanCheckedOk(int ms, String time) {
    return 'تم التحقق دون اتصال في $ms ms · $time';
  }

  @override
  String scanCheckedRefused(int ms, String time) {
    return 'تم الفحص دون اتصال في $ms ms · $time';
  }

  @override
  String get factZone => 'المدرج';

  @override
  String get factHolder => 'الحامل';

  @override
  String get factTicketNo => 'رقم التذكرة';

  @override
  String get factType => 'النوع';

  @override
  String get tokenRotating => 'التطبيق · QR متغير';

  @override
  String get tokenStatic => 'PDF · QR ثابت';

  @override
  String get resultOK => 'صالحة';

  @override
  String get resultDUPLICATE => 'مستعملة';

  @override
  String get resultBAD_SIG => 'غير صالحة';

  @override
  String get resultBAD_FORMAT => 'غير مقروءة';

  @override
  String get resultUNKNOWN => 'غير معروفة';

  @override
  String get resultCANCELLED => 'ملغاة';

  @override
  String get resultBLOCKED => 'محظورة';

  @override
  String get resultWRONG_ZONE => 'بوابة خاطئة';

  @override
  String get resultWRONG_EVENT => 'حدث آخر';

  @override
  String get resultEXPIRED_SLOT => 'رمز منتهي';

  @override
  String get resultOVERRIDE => 'استثناء';

  @override
  String reasonDuplicate(String time, String gate) {
    return 'دخل على $time · البوابة $gate';
  }

  @override
  String get reasonBadSig => 'توقيع غير صالح: تذكرة مزورة أو معدلة.';

  @override
  String get reasonBadFormat => 'هذا الرمز ليس تذكرة جمهور.';

  @override
  String get reasonUnknown => 'التذكرة غير موجودة في بيان هذا الجهاز.';

  @override
  String get reasonCancelled => 'تذكرة ملغاة أو مستردة.';

  @override
  String get reasonBlocked => 'تذكرة محظورة من طرف المنظم.';

  @override
  String reasonWrongZone(String gates) {
    return 'بوابات هذا المدرج: $gates';
  }

  @override
  String get reasonWrongZoneNone => 'لا توجد بوابة لهذا المدرج.';

  @override
  String get reasonWrongEvent => 'تذكرة لحدث آخر.';

  @override
  String get reasonExpiredSlot => 'رمز QR منتهي: اطلب من المشجع إعادة فتح التذكرة في التطبيق.';

  @override
  String get reasonOverride => 'دخول بإذن من مشرف.';

  @override
  String reasonDuplicateNoGate(String time) {
    return 'دخل على $time';
  }

  @override
  String get scanCountersStale => 'العدادات غير متاحة';

  @override
  String get enrolTitle => 'التسجيل';

  @override
  String get enrolScan => 'امسح رمز QR للتسجيل';

  @override
  String get enrolCodeRead => 'تمت قراءة رمز التسجيل';

  @override
  String get enrolCodeInvalid => 'هذا ليس رمز تسجيل Jomhor.';

  @override
  String get enrolLabel => 'اسم الجهاز';

  @override
  String get enrolLabelHint => 'مثال: لوحة الباب 3';

  @override
  String get enrolLabelInvalid => 'من 1 إلى 40 حرفًا.';

  @override
  String get enrolSubmit => 'تسجيل الجهاز';

  @override
  String get enrolFingerprint => 'البصمة';

  @override
  String get enrolPendingHint => 'تحقق من أن لوحة الإدارة تعرض نفس البصمة، ثم وافق على الجهاز.';

  @override
  String get errorNetwork => 'لا اتصال بالخادم. أعد المحاولة.';

  @override
  String get errorEnrolCode => 'رمز التسجيل غير صالح أو منتهي. اعرض رمزًا جديدًا في لوحة الإدارة.';

  @override
  String get errorGeneric => 'خطأ في الخادم. أعد المحاولة.';

  @override
  String get eventTitle => 'الحدث';

  @override
  String get eventNone => 'لا يوجد حدث مخصص لهذا الجهاز.';

  @override
  String get eventNoneHint => 'خصص الجهاز لبوابة في لوحة الإدارة (الحدث، تبويب الأجهزة).';

  @override
  String eventGate(String code, String name) {
    return 'البوابة $code · $name';
  }

  @override
  String eventManifestAt(String time) {
    return 'البيان متاح على الساعة $time';
  }

  @override
  String get eventLoad => 'تحميل البيان';

  @override
  String get eventRetry => 'أعد المحاولة';

  @override
  String get eventStageDownloading => 'جارٍ تنزيل البيان…';

  @override
  String get eventStageVerifying => 'جارٍ التحقق من التوقيع…';

  @override
  String get eventStageSaving => 'جارٍ الحفظ…';

  @override
  String eventLoaded(int version, int rows) {
    return 'البيان v$version · $rows تذكرة';
  }

  @override
  String eventDriftOk(int seconds) {
    return 'الساعة متحقق منها · فرق $seconds ث';
  }

  @override
  String eventDriftRefused(int seconds) {
    return 'الساعة متأخرة أو متقدمة بـ $seconds ث (الحد 20 ث). اضبط وقت الجهاز ثم أعد المحاولة.';
  }

  @override
  String get eventDriftUnknown => 'لم يتم التحقق من الساعة: صِل الجهاز بالشبكة.';

  @override
  String get eventRejected => 'تم رفض البيان: توقيع أو محتوى غير صالح. يُحتفظ بآخر بيان صالح.';

  @override
  String eventNotReady(String time) {
    return 'البيان غير متاح بعد (على الساعة $time).';
  }

  @override
  String get eventNotReadyNoTime => 'البيان غير متاح بعد.';

  @override
  String get eventClosed => 'تم إغلاق الحدث.';

  @override
  String get unlockTitle => 'فتح القفل';

  @override
  String get unlockPickName => 'من أنت؟';

  @override
  String get unlockNoStaff => 'لا يوجد طاقم بوابة. صِل الجهاز لتحميل القائمة.';

  @override
  String unlockPin(String name) {
    return 'الرمز السري لـ $name';
  }

  @override
  String unlockWrong(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'رمز خاطئ · $count محاولات متبقية',
      one: 'رمز خاطئ · محاولة واحدة متبقية',
    );
    return '$_temp0';
  }

  @override
  String unlockLocked(String time) {
    return 'محاولات كثيرة. أعد المحاولة على الساعة $time.';
  }

  @override
  String get unlockChangeName => 'تغيير الاسم';

  @override
  String get unlockDelete => 'حذف';

  @override
  String get roleSupervisor => 'مشرف';

  @override
  String get roleGate => 'مراقب';
}
