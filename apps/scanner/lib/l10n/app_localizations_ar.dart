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
  String get homeOpenScanner => 'فتح الماسح';

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
  String scanUnsynced(int count) {
    return '$count للمزامنة';
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
}
