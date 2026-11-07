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
  String scanSyncOnline(int count) {
    return 'EN LIGNE · $count à synchroniser';
  }

  @override
  String scanSyncOffline(int count) {
    return 'HORS LIGNE · $count à synchroniser';
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

  @override
  String get enrolTitle => 'Enrôlement';

  @override
  String get enrolScan => 'Scanner le QR d\'enrôlement';

  @override
  String get enrolCodeRead => 'QR d\'enrôlement lu';

  @override
  String get enrolCodeInvalid => 'Ce QR n\'est pas un QR d\'enrôlement Jomhor.';

  @override
  String get enrolLabel => 'Nom de l\'appareil';

  @override
  String get enrolLabelHint => 'Ex. Tablette Bâb 3';

  @override
  String get enrolLabelInvalid => 'Entre 1 et 40 caractères.';

  @override
  String get enrolSubmit => 'Enrôler l\'appareil';

  @override
  String get enrolFingerprint => 'EMPREINTE';

  @override
  String get enrolPendingHint => 'Vérifiez que le back-office affiche la même empreinte, puis approuvez l\'appareil.';

  @override
  String get errorNetwork => 'Pas de connexion au serveur. Réessayez.';

  @override
  String get errorEnrolCode => 'QR d\'enrôlement invalide ou expiré. Affichez-en un nouveau dans le back-office.';

  @override
  String get errorGeneric => 'Erreur du serveur. Réessayez.';

  @override
  String get eventTitle => 'Événement';

  @override
  String get eventNone => 'Aucun événement assigné à cet appareil.';

  @override
  String get eventNoneHint => 'Assignez l\'appareil à une porte dans le back-office (événement, onglet Appareils).';

  @override
  String eventGate(String code, String name) {
    return 'Porte $code · $name';
  }

  @override
  String eventManifestAt(String time) {
    return 'Manifeste disponible à $time';
  }

  @override
  String get eventLoad => 'Charger le manifeste';

  @override
  String get eventRetry => 'Réessayer';

  @override
  String get eventStageDownloading => 'Téléchargement du manifeste…';

  @override
  String get eventStageVerifying => 'Vérification de la signature…';

  @override
  String get eventStageSaving => 'Enregistrement…';

  @override
  String eventLoaded(int version, int rows) {
    return 'Manifeste v$version · $rows billets';
  }

  @override
  String eventDriftOk(int seconds) {
    return 'Horloge vérifiée · écart $seconds s';
  }

  @override
  String eventDriftRefused(int seconds) {
    return 'Horloge décalée de $seconds s (max 20 s). Réglez l\'heure de l\'appareil, puis réessayez.';
  }

  @override
  String get eventDriftUnknown => 'Horloge non vérifiée : connectez l\'appareil au réseau.';

  @override
  String get eventRejected =>
      'Manifeste refusé : signature ou contenu invalide. Le dernier manifeste valide est conservé.';

  @override
  String eventNotReady(String time) {
    return 'Manifeste pas encore disponible (à $time).';
  }

  @override
  String get eventNotReadyNoTime => 'Manifeste pas encore disponible.';

  @override
  String get eventClosed => 'Événement clôturé.';

  @override
  String get unlockTitle => 'Déverrouillage';

  @override
  String get unlockPickName => 'Qui êtes-vous ?';

  @override
  String get unlockNoStaff => 'Aucun membre de l\'équipe de porte. Connectez l\'appareil pour charger la liste.';

  @override
  String unlockPin(String name) {
    return 'PIN de $name';
  }

  @override
  String unlockWrong(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'PIN incorrect · $count essais restants',
      one: 'PIN incorrect · 1 essai restant',
    );
    return '$_temp0';
  }

  @override
  String unlockLocked(String time) {
    return 'Trop d\'essais. Réessayez à $time.';
  }

  @override
  String get unlockChangeName => 'Changer de nom';

  @override
  String get unlockDelete => 'Effacer';

  @override
  String get roleSupervisor => 'Superviseur';

  @override
  String get roleGate => 'Contrôleur';
}
