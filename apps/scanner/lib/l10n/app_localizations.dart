import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_ar.dart';
import 'app_localizations_en.dart';
import 'app_localizations_fr.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale) : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate = _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates = <LocalizationsDelegate<dynamic>>[
    delegate,
    GlobalMaterialLocalizations.delegate,
    GlobalCupertinoLocalizations.delegate,
    GlobalWidgetsLocalizations.delegate,
  ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[Locale('fr'), Locale('ar'), Locale('en')];

  /// Scanner app name in the OS task switcher and top bar. Brand name, not translated.
  ///
  /// In fr, this message translates to:
  /// **'Jomhor Porte'**
  String get appTitle;

  /// Home status: no device key registered with the server yet.
  ///
  /// In fr, this message translates to:
  /// **'Appareil non enrôlé'**
  String get deviceNotEnrolled;

  /// No description provided for @deviceNotEnrolledHint.
  ///
  /// In fr, this message translates to:
  /// **'Scannez le QR d\'enrôlement affiché dans le back-office, onglet Appareils.'**
  String get deviceNotEnrolledHint;

  /// Home status: enrolled, the admin has not approved the device yet (DESIGN Lot 2 adjustments).
  ///
  /// In fr, this message translates to:
  /// **'En attente d\'approbation'**
  String get devicePending;

  /// Home status: the admin approved the device.
  ///
  /// In fr, this message translates to:
  /// **'Appareil actif'**
  String get deviceActive;

  /// Scan screen when no event is loaded, the gate is unset, the device is locked or K_e is absent.
  ///
  /// In fr, this message translates to:
  /// **'Mode événement inactif'**
  String get scanInactive;

  /// No description provided for @scanInactiveHint.
  ///
  /// In fr, this message translates to:
  /// **'Chargez le manifeste de l\'événement et déverrouillez l\'appareil avec votre PIN.'**
  String get scanInactiveHint;

  /// Scanner mode label. Lot 2 has entry only (plan lot-2 Q33).
  ///
  /// In fr, this message translates to:
  /// **'ENTRÉE'**
  String get scanModeIn;

  /// Screen 6 top line: gate code, gate name, mode.
  ///
  /// In fr, this message translates to:
  /// **'PORTE {code} · {name} · {mode}'**
  String scanTopLine(String code, String name, String mode);

  /// Top line, right: the last sync round reached the server; scan rows not yet acked.
  ///
  /// In fr, this message translates to:
  /// **'EN LIGNE · {count} à synchroniser'**
  String scanSyncOnline(int count);

  /// Top line, right: no sync since start, or the last round failed; validation goes on offline.
  ///
  /// In fr, this message translates to:
  /// **'HORS LIGNE · {count} à synchroniser'**
  String scanSyncOffline(int count);

  /// Under the camera viewfinder; PDA triggers type into the same screen.
  ///
  /// In fr, this message translates to:
  /// **'Présentez le QR du billet'**
  String get scanHint;

  /// Camera failed or permission refused; keyboard-wedge input still works.
  ///
  /// In fr, this message translates to:
  /// **'Caméra indisponible. Utilisez la gâchette du terminal.'**
  String get scanCameraError;

  /// Counter: admitted at this gate on this device.
  ///
  /// In fr, this message translates to:
  /// **'ENTRÉS ICI'**
  String get scanEntered;

  /// Counter: refused at this gate on this device.
  ///
  /// In fr, this message translates to:
  /// **'REFUSÉS'**
  String get scanRefusedCount;

  /// Counter: admitted per minute over the last 5 min.
  ///
  /// In fr, this message translates to:
  /// **'CADENCE'**
  String get scanRate;

  /// No description provided for @scanRateValue.
  ///
  /// In fr, this message translates to:
  /// **'{count} / min'**
  String scanRateValue(int count);

  /// Result screen primary button: back to the camera.
  ///
  /// In fr, this message translates to:
  /// **'Scanner le suivant'**
  String get scanNext;

  /// The scan row could not be written: nothing was decided.
  ///
  /// In fr, this message translates to:
  /// **'ERREUR'**
  String get scanFailed;

  /// No description provided for @scanFailedReason.
  ///
  /// In fr, this message translates to:
  /// **'Scan non enregistré. Rescannez le billet.'**
  String get scanFailedReason;

  /// Green result footer: local validation time and clock time.
  ///
  /// In fr, this message translates to:
  /// **'Validé hors ligne en {ms} ms · {time}'**
  String scanCheckedOk(int ms, String time);

  /// Red or amber result footer.
  ///
  /// In fr, this message translates to:
  /// **'Contrôlé hors ligne en {ms} ms · {time}'**
  String scanCheckedRefused(int ms, String time);

  /// No description provided for @factZone.
  ///
  /// In fr, this message translates to:
  /// **'ZONE'**
  String get factZone;

  /// Holder initials only (Y. A.).
  ///
  /// In fr, this message translates to:
  /// **'TITULAIRE'**
  String get factHolder;

  /// No description provided for @factTicketNo.
  ///
  /// In fr, this message translates to:
  /// **'N° BILLET'**
  String get factTicketNo;

  /// No description provided for @factType.
  ///
  /// In fr, this message translates to:
  /// **'TYPE'**
  String get factType;

  /// Token v2.
  ///
  /// In fr, this message translates to:
  /// **'App · QR tournant'**
  String get tokenRotating;

  /// Token v1.
  ///
  /// In fr, this message translates to:
  /// **'PDF · QR fixe'**
  String get tokenStatic;

  /// docs/06 screen word.
  ///
  /// In fr, this message translates to:
  /// **'VALIDE'**
  String get resultOK;

  /// No description provided for @resultDUPLICATE.
  ///
  /// In fr, this message translates to:
  /// **'DÉJÀ UTILISÉ'**
  String get resultDUPLICATE;

  /// No description provided for @resultBAD_SIG.
  ///
  /// In fr, this message translates to:
  /// **'NON VALIDE'**
  String get resultBAD_SIG;

  /// No description provided for @resultBAD_FORMAT.
  ///
  /// In fr, this message translates to:
  /// **'ILLISIBLE'**
  String get resultBAD_FORMAT;

  /// No description provided for @resultUNKNOWN.
  ///
  /// In fr, this message translates to:
  /// **'INCONNU'**
  String get resultUNKNOWN;

  /// No description provided for @resultCANCELLED.
  ///
  /// In fr, this message translates to:
  /// **'ANNULÉ'**
  String get resultCANCELLED;

  /// No description provided for @resultBLOCKED.
  ///
  /// In fr, this message translates to:
  /// **'BLOQUÉ'**
  String get resultBLOCKED;

  /// No description provided for @resultWRONG_ZONE.
  ///
  /// In fr, this message translates to:
  /// **'MAUVAISE PORTE'**
  String get resultWRONG_ZONE;

  /// No description provided for @resultWRONG_EVENT.
  ///
  /// In fr, this message translates to:
  /// **'AUTRE ÉVÉNEMENT'**
  String get resultWRONG_EVENT;

  /// No description provided for @resultEXPIRED_SLOT.
  ///
  /// In fr, this message translates to:
  /// **'CODE EXPIRÉ'**
  String get resultEXPIRED_SLOT;

  /// No description provided for @resultOVERRIDE.
  ///
  /// In fr, this message translates to:
  /// **'DÉROGATION'**
  String get resultOVERRIDE;

  /// DUPLICATE: time and gate of the first scan.
  ///
  /// In fr, this message translates to:
  /// **'Entré à {time} · porte {gate}'**
  String reasonDuplicate(String time, String gate);

  /// No description provided for @reasonBadSig.
  ///
  /// In fr, this message translates to:
  /// **'Signature invalide : billet falsifié ou modifié.'**
  String get reasonBadSig;

  /// No description provided for @reasonBadFormat.
  ///
  /// In fr, this message translates to:
  /// **'Ce code n\'est pas un billet Jomhor.'**
  String get reasonBadFormat;

  /// No description provided for @reasonUnknown.
  ///
  /// In fr, this message translates to:
  /// **'Billet absent du manifeste de cet appareil.'**
  String get reasonUnknown;

  /// No description provided for @reasonCancelled.
  ///
  /// In fr, this message translates to:
  /// **'Billet annulé ou remboursé.'**
  String get reasonCancelled;

  /// No description provided for @reasonBlocked.
  ///
  /// In fr, this message translates to:
  /// **'Billet bloqué par l\'organisateur.'**
  String get reasonBlocked;

  /// WRONG_ZONE: the gate codes that serve the ticket's zone, comma-separated.
  ///
  /// In fr, this message translates to:
  /// **'Portes de cette zone : {gates}'**
  String reasonWrongZone(String gates);

  /// No description provided for @reasonWrongZoneNone.
  ///
  /// In fr, this message translates to:
  /// **'Aucune porte ne dessert cette zone.'**
  String get reasonWrongZoneNone;

  /// No description provided for @reasonWrongEvent.
  ///
  /// In fr, this message translates to:
  /// **'Billet d\'un autre événement.'**
  String get reasonWrongEvent;

  /// No description provided for @reasonExpiredSlot.
  ///
  /// In fr, this message translates to:
  /// **'QR expiré : demandez au supporter de rouvrir son billet dans l\'app.'**
  String get reasonExpiredSlot;

  /// No description provided for @reasonOverride.
  ///
  /// In fr, this message translates to:
  /// **'Entrée accordée par un superviseur.'**
  String get reasonOverride;

  /// DUPLICATE when the first scan has no gate code (remote row without one).
  ///
  /// In fr, this message translates to:
  /// **'Entré à {time}'**
  String reasonDuplicateNoGate(String time);

  /// Under the counters card when the counters could not be refreshed; they keep their last values.
  ///
  /// In fr, this message translates to:
  /// **'Compteurs indisponibles'**
  String get scanCountersStale;

  /// Enrolment screen title (DESIGN Lot 2 adjustments).
  ///
  /// In fr, this message translates to:
  /// **'Enrôlement'**
  String get enrolTitle;

  /// Opens the camera for the back-office enrol QR.
  ///
  /// In fr, this message translates to:
  /// **'Scanner le QR d\'enrôlement'**
  String get enrolScan;

  /// No description provided for @enrolCodeRead.
  ///
  /// In fr, this message translates to:
  /// **'QR d\'enrôlement lu'**
  String get enrolCodeRead;

  /// A scanned QR that is not an enrol code (a ticket, say).
  ///
  /// In fr, this message translates to:
  /// **'Ce QR n\'est pas un QR d\'enrôlement Jomhor.'**
  String get enrolCodeInvalid;

  /// Device label input, 1 to 40 characters, shown in the back-office.
  ///
  /// In fr, this message translates to:
  /// **'Nom de l\'appareil'**
  String get enrolLabel;

  /// No description provided for @enrolLabelHint.
  ///
  /// In fr, this message translates to:
  /// **'Ex. Tablette Bâb 3'**
  String get enrolLabelHint;

  /// No description provided for @enrolLabelInvalid.
  ///
  /// In fr, this message translates to:
  /// **'Entre 1 et 40 caractères.'**
  String get enrolLabelInvalid;

  /// No description provided for @enrolSubmit.
  ///
  /// In fr, this message translates to:
  /// **'Enrôler l\'appareil'**
  String get enrolSubmit;

  /// Label above the device key fingerprint (AB12 CD34).
  ///
  /// In fr, this message translates to:
  /// **'EMPREINTE'**
  String get enrolFingerprint;

  /// No description provided for @enrolPendingHint.
  ///
  /// In fr, this message translates to:
  /// **'Vérifiez que le back-office affiche la même empreinte, puis approuvez l\'appareil.'**
  String get enrolPendingHint;

  /// No description provided for @errorNetwork.
  ///
  /// In fr, this message translates to:
  /// **'Pas de connexion au serveur. Réessayez.'**
  String get errorNetwork;

  /// No description provided for @errorEnrolCode.
  ///
  /// In fr, this message translates to:
  /// **'QR d\'enrôlement invalide ou expiré. Affichez-en un nouveau dans le back-office.'**
  String get errorEnrolCode;

  /// No description provided for @errorGeneric.
  ///
  /// In fr, this message translates to:
  /// **'Erreur du serveur. Réessayez.'**
  String get errorGeneric;

  /// Event mode screen title.
  ///
  /// In fr, this message translates to:
  /// **'Événement'**
  String get eventTitle;

  /// No description provided for @eventNone.
  ///
  /// In fr, this message translates to:
  /// **'Aucun événement assigné à cet appareil.'**
  String get eventNone;

  /// No description provided for @eventNoneHint.
  ///
  /// In fr, this message translates to:
  /// **'Assignez l\'appareil à une porte dans le back-office (événement, onglet Appareils).'**
  String get eventNoneHint;

  /// Assigned gate.
  ///
  /// In fr, this message translates to:
  /// **'Porte {code} · {name}'**
  String eventGate(String code, String name);

  /// When the server serves the manifest, in the event's zone.
  ///
  /// In fr, this message translates to:
  /// **'Manifeste disponible à {time}'**
  String eventManifestAt(String time);

  /// No description provided for @eventLoad.
  ///
  /// In fr, this message translates to:
  /// **'Charger le manifeste'**
  String get eventLoad;

  /// No description provided for @eventRetry.
  ///
  /// In fr, this message translates to:
  /// **'Réessayer'**
  String get eventRetry;

  /// No description provided for @eventStageDownloading.
  ///
  /// In fr, this message translates to:
  /// **'Téléchargement du manifeste…'**
  String get eventStageDownloading;

  /// No description provided for @eventStageVerifying.
  ///
  /// In fr, this message translates to:
  /// **'Vérification de la signature…'**
  String get eventStageVerifying;

  /// No description provided for @eventStageSaving.
  ///
  /// In fr, this message translates to:
  /// **'Enregistrement…'**
  String get eventStageSaving;

  /// Last manifest applied.
  ///
  /// In fr, this message translates to:
  /// **'Manifeste v{version} · {rows} billets'**
  String eventLoaded(int version, int rows);

  /// Drift check passed (docs/02: at most 20 s).
  ///
  /// In fr, this message translates to:
  /// **'Horloge vérifiée · écart {seconds} s'**
  String eventDriftOk(int seconds);

  /// Drift check failed: event mode refused.
  ///
  /// In fr, this message translates to:
  /// **'Horloge décalée de {seconds} s (max 20 s). Réglez l\'heure de l\'appareil, puis réessayez.'**
  String eventDriftRefused(int seconds);

  /// No description provided for @eventDriftUnknown.
  ///
  /// In fr, this message translates to:
  /// **'Horloge non vérifiée : connectez l\'appareil au réseau.'**
  String get eventDriftUnknown;

  /// No description provided for @eventRejected.
  ///
  /// In fr, this message translates to:
  /// **'Manifeste refusé : signature ou contenu invalide. Le dernier manifeste valide est conservé.'**
  String get eventRejected;

  /// MANIFEST_NOT_READY.
  ///
  /// In fr, this message translates to:
  /// **'Manifeste pas encore disponible (à {time}).'**
  String eventNotReady(String time);

  /// No description provided for @eventNotReadyNoTime.
  ///
  /// In fr, this message translates to:
  /// **'Manifeste pas encore disponible.'**
  String get eventNotReadyNoTime;

  /// No description provided for @eventClosed.
  ///
  /// In fr, this message translates to:
  /// **'Événement clôturé.'**
  String get eventClosed;

  /// Staff unlock screen title.
  ///
  /// In fr, this message translates to:
  /// **'Déverrouillage'**
  String get unlockTitle;

  /// No description provided for @unlockPickName.
  ///
  /// In fr, this message translates to:
  /// **'Qui êtes-vous ?'**
  String get unlockPickName;

  /// No description provided for @unlockNoStaff.
  ///
  /// In fr, this message translates to:
  /// **'Aucun membre de l\'équipe de porte. Connectez l\'appareil pour charger la liste.'**
  String get unlockNoStaff;

  /// Above the PIN pad.
  ///
  /// In fr, this message translates to:
  /// **'PIN de {name}'**
  String unlockPin(String name);

  /// Wrong PIN; after 5 the device locks for 5 min.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{PIN incorrect · 1 essai restant} other{PIN incorrect · {count} essais restants}}'**
  String unlockWrong(int count);

  /// PIN lockout.
  ///
  /// In fr, this message translates to:
  /// **'Trop d\'essais. Réessayez à {time}.'**
  String unlockLocked(String time);

  /// No description provided for @unlockChangeName.
  ///
  /// In fr, this message translates to:
  /// **'Changer de nom'**
  String get unlockChangeName;

  /// PIN pad backspace (screen reader).
  ///
  /// In fr, this message translates to:
  /// **'Effacer'**
  String get unlockDelete;

  /// No description provided for @roleSupervisor.
  ///
  /// In fr, this message translates to:
  /// **'Superviseur'**
  String get roleSupervisor;

  /// No description provided for @roleGate.
  ///
  /// In fr, this message translates to:
  /// **'Contrôleur'**
  String get roleGate;

  /// Supervisor sheet title and the button that opens it (scan and result screens).
  ///
  /// In fr, this message translates to:
  /// **'Superviseur'**
  String get supervisorTitle;

  /// No description provided for @supervisorPickName.
  ///
  /// In fr, this message translates to:
  /// **'Quel superviseur ?'**
  String get supervisorPickName;

  /// The staff list has no SUPERVISOR.
  ///
  /// In fr, this message translates to:
  /// **'Aucun superviseur sur cet appareil. Connectez l\'appareil pour charger l\'équipe de porte.'**
  String get supervisorNone;

  /// Closes the supervisor sheet (screen reader).
  ///
  /// In fr, this message translates to:
  /// **'Fermer'**
  String get supervisorClose;

  /// No description provided for @supervisorBack.
  ///
  /// In fr, this message translates to:
  /// **'Retour'**
  String get supervisorBack;

  /// Overrides on this device for this event, against the cap (docs/02: 20).
  ///
  /// In fr, this message translates to:
  /// **'Dérogations {count} / {cap}'**
  String supervisorOverrideCount(int count, int cap);

  /// Override refused: the per-device cap is reached.
  ///
  /// In fr, this message translates to:
  /// **'Plafond atteint : {cap} dérogations sur cet appareil pour cet événement.'**
  String supervisorCapReached(int cap);

  /// Supervisor action: admit a ticket with a reason (OVERRIDE).
  ///
  /// In fr, this message translates to:
  /// **'Dérogation'**
  String get actionOverride;

  /// No description provided for @actionGate.
  ///
  /// In fr, this message translates to:
  /// **'Changer de porte'**
  String get actionGate;

  /// No description provided for @actionLookup.
  ///
  /// In fr, this message translates to:
  /// **'Recherche billet'**
  String get actionLookup;

  /// No description provided for @overrideReasonTitle.
  ///
  /// In fr, this message translates to:
  /// **'Motif de la dérogation'**
  String get overrideReasonTitle;

  /// docs/06 override reason labels.
  ///
  /// In fr, this message translates to:
  /// **'Identité vérifiée'**
  String get reasonID_CHECKED;

  /// No description provided for @reasonDEVICE_FAULT.
  ///
  /// In fr, this message translates to:
  /// **'Panne d\'appareil'**
  String get reasonDEVICE_FAULT;

  /// No description provided for @reasonDAMAGED_TICKET.
  ///
  /// In fr, this message translates to:
  /// **'Billet abîmé'**
  String get reasonDAMAGED_TICKET;

  /// No description provided for @reasonOTHER.
  ///
  /// In fr, this message translates to:
  /// **'Autre motif'**
  String get reasonOTHER;

  /// Typed ticket number, printed under every QR (4-4).
  ///
  /// In fr, this message translates to:
  /// **'N° billet (8 caractères)'**
  String get ticketNoLabel;

  /// No description provided for @ticketSearch.
  ///
  /// In fr, this message translates to:
  /// **'Chercher'**
  String get ticketSearch;

  /// No description provided for @ticketScan.
  ///
  /// In fr, this message translates to:
  /// **'Scanner le billet'**
  String get ticketScan;

  /// Lookup or override: nothing in the manifest.
  ///
  /// In fr, this message translates to:
  /// **'Aucun billet de ce numéro dans le manifeste.'**
  String get ticketNoMatch;

  /// Override: the manifest tickets matching the number; typed numbers can collide (plan lot-2 Q40).
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{1 billet : touchez-le pour l\'admettre} other{{count} billets portent ce numéro : touchez le bon}}'**
  String ticketPick(int count);

  /// No description provided for @ticketNotInManifest.
  ///
  /// In fr, this message translates to:
  /// **'Billet absent du manifeste : dérogation impossible.'**
  String get ticketNotInManifest;

  /// Lookup row: manifest status and holder initials (no other holder data).
  ///
  /// In fr, this message translates to:
  /// **'{status} · {initials}'**
  String ticketDetail(String status, String initials);

  /// Manifest ticket statuses.
  ///
  /// In fr, this message translates to:
  /// **'Valide'**
  String get statusVALID;

  /// No description provided for @statusCANCELLED.
  ///
  /// In fr, this message translates to:
  /// **'Annulé'**
  String get statusCANCELLED;

  /// No description provided for @statusREFUNDED.
  ///
  /// In fr, this message translates to:
  /// **'Remboursé'**
  String get statusREFUNDED;

  /// No description provided for @statusBLOCKED.
  ///
  /// In fr, this message translates to:
  /// **'Bloqué'**
  String get statusBLOCKED;

  /// No description provided for @gatePickTitle.
  ///
  /// In fr, this message translates to:
  /// **'Porte de cet appareil'**
  String get gatePickTitle;

  /// Marks the device's current gate.
  ///
  /// In fr, this message translates to:
  /// **'Actuelle'**
  String get gateCurrent;

  /// Supervisor PIN older than 60 s, supervisor gone from the staff list: back to the supervisor list.
  ///
  /// In fr, this message translates to:
  /// **'Session superviseur terminée : saisissez à nouveau le PIN.'**
  String get supervisorExpired;
}

class _AppLocalizationsDelegate extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) => <String>['ar', 'en', 'fr'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'ar':
      return AppLocalizationsAr();
    case 'en':
      return AppLocalizationsEn();
    case 'fr':
      return AppLocalizationsFr();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
