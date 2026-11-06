// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Arabic (`ar`).
class AppLocalizationsAr extends AppLocalizations {
  AppLocalizationsAr([String locale = 'ar']) : super(locale);

  @override
  String get appTitle => 'Jomhor';

  @override
  String get navHome => 'الرئيسية';

  @override
  String get navTickets => 'التذاكر';

  @override
  String get navProfile => 'الملف الشخصي';

  @override
  String get placeholderComingSoon => 'Bientôt ici. On le prépare pour toi.';
}
