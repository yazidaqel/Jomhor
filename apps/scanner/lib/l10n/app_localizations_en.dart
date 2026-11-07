// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Jomhor Porte';

  @override
  String get deviceNotEnrolled => 'Device not enrolled';

  @override
  String get deviceNotEnrolledHint => 'Scannez le QR d\'enrôlement affiché dans le back-office, onglet Appareils.';

  @override
  String get devicePending => 'Waiting for approval';

  @override
  String get deviceActive => 'Device active';
}
