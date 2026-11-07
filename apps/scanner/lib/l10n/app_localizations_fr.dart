// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for French (`fr`).
class AppLocalizationsFr extends AppLocalizations {
  AppLocalizationsFr([String locale = 'fr']) : super(locale);

  @override
  String get appTitle => 'Jomhor Porte';

  @override
  String get deviceNotEnrolled => 'Appareil non enrôlé';

  @override
  String get deviceNotEnrolledHint => 'Scannez le QR d\'enrôlement affiché dans le back-office, onglet Appareils.';

  @override
  String get devicePending => 'En attente d\'approbation';

  @override
  String get deviceActive => 'Appareil actif';

  @override
  String get homeOpenScanner => 'Ouvrir le scanner';

  @override
  String get scanInactive => 'Mode événement inactif';

  @override
  String get scanInactiveHint => 'Chargez le manifeste de l\'événement et déverrouillez l\'appareil avec votre PIN.';

  @override
  String get scanModeIn => 'ENTRÉE';

  @override
  String scanTopLine(String code, String name, String mode) {
    return 'PORTE $code · $name · $mode';
  }

  @override
  String scanUnsynced(int count) {
    return '$count à synchroniser';
  }

  @override
  String get scanHint => 'Présentez le QR du billet';

  @override
  String get scanCameraError => 'Caméra indisponible. Utilisez la gâchette du terminal.';

  @override
  String get scanEntered => 'ENTRÉS ICI';

  @override
  String get scanRefusedCount => 'REFUSÉS';

  @override
  String get scanRate => 'CADENCE';

  @override
  String scanRateValue(int count) {
    return '$count / min';
  }

  @override
  String get scanNext => 'Scanner le suivant';

  @override
  String get scanFailed => 'ERREUR';

  @override
  String get scanFailedReason => 'Scan non enregistré. Rescannez le billet.';

  @override
  String scanCheckedOk(int ms, String time) {
    return 'Validé hors ligne en $ms ms · $time';
  }

  @override
  String scanCheckedRefused(int ms, String time) {
    return 'Contrôlé hors ligne en $ms ms · $time';
  }

  @override
  String get factZone => 'ZONE';

  @override
  String get factHolder => 'TITULAIRE';

  @override
  String get factTicketNo => 'N° BILLET';

  @override
  String get factType => 'TYPE';

  @override
  String get tokenRotating => 'App · QR tournant';

  @override
  String get tokenStatic => 'PDF · QR fixe';

  @override
  String get resultOK => 'VALIDE';

  @override
  String get resultDUPLICATE => 'DÉJÀ UTILISÉ';

  @override
  String get resultBAD_SIG => 'NON VALIDE';

  @override
  String get resultBAD_FORMAT => 'ILLISIBLE';

  @override
  String get resultUNKNOWN => 'INCONNU';

  @override
  String get resultCANCELLED => 'ANNULÉ';

  @override
  String get resultBLOCKED => 'BLOQUÉ';

  @override
  String get resultWRONG_ZONE => 'MAUVAISE PORTE';

  @override
  String get resultWRONG_EVENT => 'AUTRE ÉVÉNEMENT';

  @override
  String get resultEXPIRED_SLOT => 'CODE EXPIRÉ';

  @override
  String get resultOVERRIDE => 'DÉROGATION';

  @override
  String reasonDuplicate(String time, String gate) {
    return 'Entré à $time · porte $gate';
  }

  @override
  String get reasonBadSig => 'Signature invalide : billet falsifié ou modifié.';

  @override
  String get reasonBadFormat => 'Ce code n\'est pas un billet Jomhor.';

  @override
  String get reasonUnknown => 'Billet absent du manifeste de cet appareil.';

  @override
  String get reasonCancelled => 'Billet annulé ou remboursé.';

  @override
  String get reasonBlocked => 'Billet bloqué par l\'organisateur.';

  @override
  String reasonWrongZone(String gates) {
    return 'Portes de cette zone : $gates';
  }

  @override
  String get reasonWrongZoneNone => 'Aucune porte ne dessert cette zone.';

  @override
  String get reasonWrongEvent => 'Billet d\'un autre événement.';

  @override
  String get reasonExpiredSlot => 'QR expiré : demandez au supporter de rouvrir son billet dans l\'app.';

  @override
  String get reasonOverride => 'Entrée accordée par un superviseur.';

  @override
  String reasonDuplicateNoGate(String time) {
    return 'Entré à $time';
  }

  @override
  String get scanCountersStale => 'Compteurs indisponibles';
}
