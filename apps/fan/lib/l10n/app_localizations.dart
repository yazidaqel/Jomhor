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

  /// App name in the OS task switcher and top bar. Brand name, not translated.
  ///
  /// In fr, this message translates to:
  /// **'Jomhor'**
  String get appTitle;

  /// Bottom nav tab: home screen (DESIGN.md screen 1).
  ///
  /// In fr, this message translates to:
  /// **'Accueil'**
  String get navHome;

  /// Bottom nav tab: the fan's tickets (wallet).
  ///
  /// In fr, this message translates to:
  /// **'Billets'**
  String get navTickets;

  /// Bottom nav tab: the fan's profile.
  ///
  /// In fr, this message translates to:
  /// **'Profil'**
  String get navProfile;

  /// Body of a screen not built yet. Fans are addressed with 'tu'.
  ///
  /// In fr, this message translates to:
  /// **'Bientôt ici. On le prépare pour toi.'**
  String get placeholderComingSoon;

  /// Tooltip of the back button.
  ///
  /// In fr, this message translates to:
  /// **'Retour'**
  String get back;

  /// No description provided for @retry.
  ///
  /// In fr, this message translates to:
  /// **'Réessayer'**
  String get retry;

  /// No description provided for @loginTitle.
  ///
  /// In fr, this message translates to:
  /// **'Connecte-toi'**
  String get loginTitle;

  /// No description provided for @loginPhoneIntro.
  ///
  /// In fr, this message translates to:
  /// **'Entre ton numéro de téléphone. On t\'envoie un code par SMS.'**
  String get loginPhoneIntro;

  /// No description provided for @loginPhoneLabel.
  ///
  /// In fr, this message translates to:
  /// **'Numéro de téléphone'**
  String get loginPhoneLabel;

  /// Example Moroccan number shown in the empty phone field.
  ///
  /// In fr, this message translates to:
  /// **'06 12 34 56 78'**
  String get loginPhoneHint;

  /// No description provided for @loginSendCode.
  ///
  /// In fr, this message translates to:
  /// **'Recevoir le code'**
  String get loginSendCode;

  /// No description provided for @loginCodeIntro.
  ///
  /// In fr, this message translates to:
  /// **'Entre le code à 6 chiffres envoyé au {phone}.'**
  String loginCodeIntro(String phone);

  /// No description provided for @loginCodeLabel.
  ///
  /// In fr, this message translates to:
  /// **'Code à 6 chiffres'**
  String get loginCodeLabel;

  /// No description provided for @loginVerify.
  ///
  /// In fr, this message translates to:
  /// **'Valider'**
  String get loginVerify;

  /// No description provided for @loginChangePhone.
  ///
  /// In fr, this message translates to:
  /// **'Changer de numéro'**
  String get loginChangePhone;

  /// No description provided for @loginResend.
  ///
  /// In fr, this message translates to:
  /// **'Renvoyer le code'**
  String get loginResend;

  /// No description provided for @errorPhoneInvalid.
  ///
  /// In fr, this message translates to:
  /// **'Ce numéro ne semble pas marocain. Essaie par exemple 06 12 34 56 78.'**
  String get errorPhoneInvalid;

  /// No description provided for @errorOtpInvalid.
  ///
  /// In fr, this message translates to:
  /// **'Code incorrect. Vérifie-le et réessaie.'**
  String get errorOtpInvalid;

  /// No description provided for @errorOtpExpired.
  ///
  /// In fr, this message translates to:
  /// **'Ce code a expiré. Demande un nouveau code.'**
  String get errorOtpExpired;

  /// No description provided for @errorOtpRateLimited.
  ///
  /// In fr, this message translates to:
  /// **'Trop de demandes pour ce numéro. Patiente un peu avant de réessayer.'**
  String get errorOtpRateLimited;

  /// No description provided for @errorValidation.
  ///
  /// In fr, this message translates to:
  /// **'Vérifie les informations saisies.'**
  String get errorValidation;

  /// No description provided for @errorNotFound.
  ///
  /// In fr, this message translates to:
  /// **'Introuvable. Il a peut-être été retiré.'**
  String get errorNotFound;

  /// No description provided for @errorNetwork.
  ///
  /// In fr, this message translates to:
  /// **'Pas de connexion. Vérifie ton réseau et réessaie.'**
  String get errorNetwork;

  /// No description provided for @errorMissingOrganizer.
  ///
  /// In fr, this message translates to:
  /// **'Application mal configurée : aucun club défini (ORGANIZER_ID).'**
  String get errorMissingOrganizer;

  /// No description provided for @errorGeneric.
  ///
  /// In fr, this message translates to:
  /// **'Un problème est survenu. Réessaie dans un instant.'**
  String get errorGeneric;

  /// Hero label for a FOOTBALL_MATCH. Lot 1 sells home matches only. Shown uppercase.
  ///
  /// In fr, this message translates to:
  /// **'Prochain match · Domicile'**
  String get heroLabelMatch;

  /// Hero label for any other event type. Shown uppercase.
  ///
  /// In fr, this message translates to:
  /// **'Prochain événement'**
  String get heroLabelEvent;

  /// Between the two team crests on the match hero.
  ///
  /// In fr, this message translates to:
  /// **'VS'**
  String get versus;

  /// No description provided for @matchday.
  ///
  /// In fr, this message translates to:
  /// **'Journée {n}'**
  String matchday(int n);

  /// No description provided for @whenWhere.
  ///
  /// In fr, this message translates to:
  /// **'{date} · {time} · {venue}'**
  String whenWhere(String date, String time, String venue);

  /// Two facts on one line, e.g. date · time or competition · matchday.
  ///
  /// In fr, this message translates to:
  /// **'{first} · {second}'**
  String dotPair(String first, String second);

  /// No description provided for @countdownDays.
  ///
  /// In fr, this message translates to:
  /// **'Jours'**
  String get countdownDays;

  /// No description provided for @countdownHours.
  ///
  /// In fr, this message translates to:
  /// **'Heures'**
  String get countdownHours;

  /// No description provided for @countdownMinutes.
  ///
  /// In fr, this message translates to:
  /// **'Min'**
  String get countdownMinutes;

  /// No description provided for @ctaBuyFrom.
  ///
  /// In fr, this message translates to:
  /// **'Prendre mes places · dès {price}'**
  String ctaBuyFrom(String price);

  /// No description provided for @ctaBuy.
  ///
  /// In fr, this message translates to:
  /// **'Prendre mes places'**
  String get ctaBuy;

  /// No description provided for @saleOpensOn.
  ///
  /// In fr, this message translates to:
  /// **'Vente le {date} à {time}'**
  String saleOpensOn(String date, String time);

  /// The opening time has passed but the app has not seen the sale open yet (same copy as the web shop OPENING).
  ///
  /// In fr, this message translates to:
  /// **'Ouverture de la vente imminente'**
  String get saleOpening;

  /// No description provided for @soldOut.
  ///
  /// In fr, this message translates to:
  /// **'Complet'**
  String get soldOut;

  /// No description provided for @saleClosed.
  ///
  /// In fr, this message translates to:
  /// **'Vente terminée'**
  String get saleClosed;

  /// No description provided for @calendar.
  ///
  /// In fr, this message translates to:
  /// **'Calendrier'**
  String get calendar;

  /// No description provided for @chipFrom.
  ///
  /// In fr, this message translates to:
  /// **'dès {price}'**
  String chipFrom(String price);

  /// No description provided for @chipSoon.
  ///
  /// In fr, this message translates to:
  /// **'Bientôt'**
  String get chipSoon;

  /// No description provided for @homeEmpty.
  ///
  /// In fr, this message translates to:
  /// **'Pas de match à venir pour l\'instant. Reviens bientôt.'**
  String get homeEmpty;

  /// No description provided for @rulesOpen.
  ///
  /// In fr, this message translates to:
  /// **'Vente ouverte · limite {limit, plural, =1{1 billet} other{{limit} billets}} par compte · billets nominatifs'**
  String rulesOpen(int limit);

  /// No description provided for @rulesNotStarted.
  ///
  /// In fr, this message translates to:
  /// **'Vente le {date} à {time} · limite {limit, plural, =1{1 billet} other{{limit} billets}} par compte · billets nominatifs'**
  String rulesNotStarted(String date, String time, int limit);

  /// No description provided for @rulesSoldOut.
  ///
  /// In fr, this message translates to:
  /// **'Complet · toutes les places sont vendues'**
  String get rulesSoldOut;

  /// No description provided for @rulesClosed.
  ///
  /// In fr, this message translates to:
  /// **'Vente terminée pour cet événement'**
  String get rulesClosed;

  /// No description provided for @zonesTitle.
  ///
  /// In fr, this message translates to:
  /// **'Tarifs par zone'**
  String get zonesTitle;

  /// No description provided for @zoneSeated.
  ///
  /// In fr, this message translates to:
  /// **'Places assises'**
  String get zoneSeated;

  /// No description provided for @zoneStanding.
  ///
  /// In fr, this message translates to:
  /// **'Debout'**
  String get zoneStanding;

  /// No description provided for @zoneGates.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{Porte {codes}} other{Portes {codes}}}'**
  String zoneGates(int count, String codes);

  /// Places left in a zone.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{1 restant} other{{count} restants}}'**
  String zoneRemaining(int count);

  /// No description provided for @zoneLastSeats.
  ///
  /// In fr, this message translates to:
  /// **'Dernières places'**
  String get zoneLastSeats;

  /// No description provided for @paymentHint.
  ///
  /// In fr, this message translates to:
  /// **'Paiement par carte bancaire. Billet dans l\'app et sur WhatsApp.'**
  String get paymentHint;

  /// No description provided for @footerFrom.
  ///
  /// In fr, this message translates to:
  /// **'À partir de'**
  String get footerFrom;

  /// No description provided for @chooseZone.
  ///
  /// In fr, this message translates to:
  /// **'Choisir ma zone'**
  String get chooseZone;

  /// No description provided for @zonesSubtitle.
  ///
  /// In fr, this message translates to:
  /// **'{event} · {date}'**
  String zonesSubtitle(String event, String date);

  /// No description provided for @pickZoneHint.
  ///
  /// In fr, this message translates to:
  /// **'Touche une zone pour choisir tes places.'**
  String get pickZoneHint;

  /// No description provided for @pickedZone.
  ///
  /// In fr, this message translates to:
  /// **'Zone choisie'**
  String get pickedZone;

  /// No description provided for @placesMax.
  ///
  /// In fr, this message translates to:
  /// **'Billets · max {max}'**
  String placesMax(int max);

  /// No description provided for @fewerPlaces.
  ///
  /// In fr, this message translates to:
  /// **'Un billet de moins'**
  String get fewerPlaces;

  /// No description provided for @morePlaces.
  ///
  /// In fr, this message translates to:
  /// **'Un billet de plus'**
  String get morePlaces;

  /// No description provided for @groupHint.
  ///
  /// In fr, this message translates to:
  /// **'Les places d\'un même achat sont côte à côte. Chaque ami reçoit son propre QR.'**
  String get groupHint;

  /// No description provided for @total.
  ///
  /// In fr, this message translates to:
  /// **'Total'**
  String get total;

  /// No description provided for @continueCta.
  ///
  /// In fr, this message translates to:
  /// **'Continuer'**
  String get continueCta;

  /// No description provided for @allSoldOut.
  ///
  /// In fr, this message translates to:
  /// **'Complet · toutes les zones sont vendues'**
  String get allSoldOut;

  /// No description provided for @payTitle.
  ///
  /// In fr, this message translates to:
  /// **'Paiement'**
  String get payTitle;

  /// Hold countdown, mm:ss.
  ///
  /// In fr, this message translates to:
  /// **'Places réservées · {time}'**
  String holdLeft(String time);

  /// No description provided for @summaryLine.
  ///
  /// In fr, this message translates to:
  /// **'{qty} × {zone}'**
  String summaryLine(int qty, String zone);

  /// No description provided for @serviceFee.
  ///
  /// In fr, this message translates to:
  /// **'Frais de service'**
  String get serviceFee;

  /// No description provided for @holdersTitle.
  ///
  /// In fr, this message translates to:
  /// **'Billets nominatifs'**
  String get holdersTitle;

  /// No description provided for @holderMe.
  ///
  /// In fr, this message translates to:
  /// **'Billet 1 · Moi'**
  String get holderMe;

  /// No description provided for @holderN.
  ///
  /// In fr, this message translates to:
  /// **'Billet {n}'**
  String holderN(int n);

  /// No description provided for @holderNameLabel.
  ///
  /// In fr, this message translates to:
  /// **'Prénom et nom'**
  String get holderNameLabel;

  /// No description provided for @holderNameError.
  ///
  /// In fr, this message translates to:
  /// **'Prénom et nom en lettres latines, de 2 à 60 caractères.'**
  String get holderNameError;

  /// No description provided for @holderPhoneLabel.
  ///
  /// In fr, this message translates to:
  /// **'WhatsApp de ton ami (facultatif)'**
  String get holderPhoneLabel;

  /// No description provided for @holderPhoneHelp.
  ///
  /// In fr, this message translates to:
  /// **'Il reçoit son billet sur WhatsApp et dans l\'app. Sans numéro, le billet reste chez toi.'**
  String get holderPhoneHelp;

  /// No description provided for @holderPhoneError.
  ///
  /// In fr, this message translates to:
  /// **'Numéro marocain invalide. Essaie 06 12 34 56 78.'**
  String get holderPhoneError;

  /// No description provided for @methodTitle.
  ///
  /// In fr, this message translates to:
  /// **'Moyen de paiement'**
  String get methodTitle;

  /// No description provided for @methodCard.
  ///
  /// In fr, this message translates to:
  /// **'Carte bancaire'**
  String get methodCard;

  /// No description provided for @methodCardHint.
  ///
  /// In fr, this message translates to:
  /// **'Visa, Mastercard · 3D Secure'**
  String get methodCardHint;

  /// No description provided for @instant.
  ///
  /// In fr, this message translates to:
  /// **'Instantané'**
  String get instant;

  /// No description provided for @payCta.
  ///
  /// In fr, this message translates to:
  /// **'Payer {total}'**
  String payCta(String total);

  /// No description provided for @payCaption.
  ///
  /// In fr, this message translates to:
  /// **'Billets envoyés dans l\'app et sur WhatsApp'**
  String get payCaption;

  /// Plan Q21: required as soon as a holder row has a friend's phone.
  ///
  /// In fr, this message translates to:
  /// **'Je confirme que ces personnes acceptent de recevoir leur billet sur WhatsApp'**
  String get friendsConsent;

  /// No description provided for @friendsConsentMissing.
  ///
  /// In fr, this message translates to:
  /// **'Coche la case pour confirmer que tes amis sont d\'accord.'**
  String get friendsConsentMissing;

  /// No description provided for @checkPayment.
  ///
  /// In fr, this message translates to:
  /// **'Voir l\'état du paiement'**
  String get checkPayment;

  /// SALE_BUSY: the app retries by itself. No queue position exists, none is shown.
  ///
  /// In fr, this message translates to:
  /// **'Beaucoup de monde en ce moment · nouvel essai dans {s} s'**
  String busyRetry(int s);

  /// No description provided for @expiredTitle.
  ///
  /// In fr, this message translates to:
  /// **'Réservation expirée'**
  String get expiredTitle;

  /// No description provided for @expiredBody.
  ///
  /// In fr, this message translates to:
  /// **'Tes places ont été libérées. Choisis-les à nouveau.'**
  String get expiredBody;

  /// No description provided for @expiredCta.
  ///
  /// In fr, this message translates to:
  /// **'Choisir à nouveau'**
  String get expiredCta;

  /// No description provided for @cardPageTitle.
  ///
  /// In fr, this message translates to:
  /// **'Paiement sécurisé'**
  String get cardPageTitle;

  /// No description provided for @orderPending.
  ///
  /// In fr, this message translates to:
  /// **'Paiement en cours de confirmation.'**
  String get orderPending;

  /// No description provided for @orderPendingHint.
  ///
  /// In fr, this message translates to:
  /// **'Ça prend quelques secondes. Ne paie pas une deuxième fois : la banque peut confirmer un peu plus tard.'**
  String get orderPendingHint;

  /// No description provided for @orderPaymentFailed.
  ///
  /// In fr, this message translates to:
  /// **'Ton paiement n\'a pas abouti. Tu peux réessayer tant que tes places sont réservées.'**
  String get orderPaymentFailed;

  /// No description provided for @orderAwaitingPayment.
  ///
  /// In fr, this message translates to:
  /// **'Commande en attente de paiement.'**
  String get orderAwaitingPayment;

  /// No description provided for @orderNotPaidYet.
  ///
  /// In fr, this message translates to:
  /// **'Aucun paiement n\'a encore été fait pour cette commande. Tu peux payer tant que tes places sont réservées.'**
  String get orderNotPaidYet;

  /// No description provided for @orderPayAgainCaution.
  ///
  /// In fr, this message translates to:
  /// **'Toujours pas de confirmation. Réessaie seulement si ta carte a été refusée et que rien n\'a été débité.'**
  String get orderPayAgainCaution;

  /// No description provided for @payAgain.
  ///
  /// In fr, this message translates to:
  /// **'Payer à nouveau'**
  String get payAgain;

  /// No description provided for @orderPaidTitle.
  ///
  /// In fr, this message translates to:
  /// **'C\'est validé !'**
  String get orderPaidTitle;

  /// No description provided for @orderPaidBody.
  ///
  /// In fr, this message translates to:
  /// **'Tes billets sont dans l\'onglet Billets. Tes amis reçoivent les leurs sur WhatsApp.'**
  String get orderPaidBody;

  /// No description provided for @seeTickets.
  ///
  /// In fr, this message translates to:
  /// **'Voir mes billets'**
  String get seeTickets;

  /// No description provided for @orderExpired.
  ///
  /// In fr, this message translates to:
  /// **'Cette réservation a expiré.'**
  String get orderExpired;

  /// No description provided for @backHome.
  ///
  /// In fr, this message translates to:
  /// **'Retour à l\'accueil'**
  String get backHome;

  /// No description provided for @errorNotStarted.
  ///
  /// In fr, this message translates to:
  /// **'La vente n\'a pas encore commencé.'**
  String get errorNotStarted;

  /// No description provided for @errorSaleClosed.
  ///
  /// In fr, this message translates to:
  /// **'La vente est terminée pour cet événement.'**
  String get errorSaleClosed;

  /// No description provided for @errorNotOnSale.
  ///
  /// In fr, this message translates to:
  /// **'Cet événement n\'est pas en vente.'**
  String get errorNotOnSale;

  /// No description provided for @errorSoldOut.
  ///
  /// In fr, this message translates to:
  /// **'Plus de places dans ce tarif. Choisis une autre zone.'**
  String get errorSoldOut;

  /// No description provided for @errorPerAccountLimit.
  ///
  /// In fr, this message translates to:
  /// **'Limite atteinte : {limit} billets par compte pour cet événement, tu en as déjà {held}.'**
  String errorPerAccountLimit(int limit, int held);

  /// No description provided for @errorOrderExpired.
  ///
  /// In fr, this message translates to:
  /// **'Ta réservation a expiré. Choisis à nouveau tes places.'**
  String get errorOrderExpired;

  /// No description provided for @errorOrderNotPayable.
  ///
  /// In fr, this message translates to:
  /// **'Cette commande ne peut plus être payée.'**
  String get errorOrderNotPayable;

  /// No description provided for @close.
  ///
  /// In fr, this message translates to:
  /// **'Fermer'**
  String get close;

  /// No description provided for @ticketsTitle.
  ///
  /// In fr, this message translates to:
  /// **'Mes billets'**
  String get ticketsTitle;

  /// No description provided for @ticketsUpcoming.
  ///
  /// In fr, this message translates to:
  /// **'À venir'**
  String get ticketsUpcoming;

  /// No description provided for @ticketsPast.
  ///
  /// In fr, this message translates to:
  /// **'Passés'**
  String get ticketsPast;

  /// No description provided for @ticketsEmpty.
  ///
  /// In fr, this message translates to:
  /// **'Aucun billet pour l\'instant. Tes billets arrivent ici dès que le paiement est confirmé.'**
  String get ticketsEmpty;

  /// No description provided for @ticketsBrowse.
  ///
  /// In fr, this message translates to:
  /// **'Voir les matchs'**
  String get ticketsBrowse;

  /// The refresh failed; the tickets saved on the phone are shown (QR included).
  ///
  /// In fr, this message translates to:
  /// **'Hors ligne · billets enregistrés sur ton téléphone'**
  String get ticketsOffline;

  /// No description provided for @ticketRowFacts.
  ///
  /// In fr, this message translates to:
  /// **'{zone} · N° {ticketNo}'**
  String ticketRowFacts(String zone, String ticketNo);

  /// Screen 5 header from kickoff - 4 h. Shown uppercase.
  ///
  /// In fr, this message translates to:
  /// **'Mode jour de match'**
  String get ticketMatchday;

  /// Screen 5 header icon: the screen is at full brightness while the ticket is shown.
  ///
  /// In fr, this message translates to:
  /// **'Luminosité au maximum'**
  String get ticketBrightness;

  /// No description provided for @ticketZone.
  ///
  /// In fr, this message translates to:
  /// **'Zone'**
  String get ticketZone;

  /// No description provided for @ticketGates.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{Porte} other{Portes}}'**
  String ticketGates(int count);

  /// Gate codes after the label, e.g. Porte 4 or Portes 3, 4.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{Porte {codes}} other{Portes {codes}}}'**
  String ticketGatesList(int count, String codes);

  /// Between items of a short list (gate codes). Arabic uses the Arabic comma.
  ///
  /// In fr, this message translates to:
  /// **', '**
  String get listSeparator;

  /// No description provided for @ticketKickoff.
  ///
  /// In fr, this message translates to:
  /// **'Coup d\'envoi'**
  String get ticketKickoff;

  /// Start time label for an event that is not a match.
  ///
  /// In fr, this message translates to:
  /// **'Début'**
  String get ticketStart;

  /// No description provided for @ticketHolder.
  ///
  /// In fr, this message translates to:
  /// **'Titulaire'**
  String get ticketHolder;

  /// No description provided for @ticketNumber.
  ///
  /// In fr, this message translates to:
  /// **'N° billet'**
  String get ticketNumber;

  /// No description provided for @ticketSeat.
  ///
  /// In fr, this message translates to:
  /// **'Place'**
  String get ticketSeat;

  /// No description provided for @ticketNextCode.
  ///
  /// In fr, this message translates to:
  /// **'Nouveau code dans {s} s'**
  String ticketNextCode(int s);

  /// No description provided for @ticketWorksOffline.
  ///
  /// In fr, this message translates to:
  /// **'Fonctionne sans réseau'**
  String get ticketWorksOffline;

  /// Screen reader label of the QR.
  ///
  /// In fr, this message translates to:
  /// **'Code QR du billet {ticketNo}'**
  String ticketQrLabel(String ticketNo);

  /// No description provided for @ticketClosed.
  ///
  /// In fr, this message translates to:
  /// **'Événement terminé'**
  String get ticketClosed;

  /// No description provided for @ticketCancelled.
  ///
  /// In fr, this message translates to:
  /// **'Billet annulé'**
  String get ticketCancelled;

  /// No description provided for @ticketRefunded.
  ///
  /// In fr, this message translates to:
  /// **'Billet remboursé'**
  String get ticketRefunded;

  /// No description provided for @ticketBlocked.
  ///
  /// In fr, this message translates to:
  /// **'Billet bloqué. Contacte le club.'**
  String get ticketBlocked;

  /// No description provided for @ticketNotValid.
  ///
  /// In fr, this message translates to:
  /// **'Billet non valide'**
  String get ticketNotValid;

  /// No description provided for @ticketNotFound.
  ///
  /// In fr, this message translates to:
  /// **'Ce billet n\'est pas sur ton téléphone.'**
  String get ticketNotFound;

  /// No description provided for @ticketWhatsApp.
  ///
  /// In fr, this message translates to:
  /// **'WhatsApp'**
  String get ticketWhatsApp;

  /// Lot 1: the re-send endpoint (BE-14) is not there yet, so the button is disabled.
  ///
  /// In fr, this message translates to:
  /// **'Renvoi du PDF sur WhatsApp · bientôt'**
  String get ticketWhatsAppSoon;

  /// Screen 1 strip. what = competition or event title.
  ///
  /// In fr, this message translates to:
  /// **'Mon billet · {what}, {date}'**
  String myTicketTitle(String what, String date);

  /// Screen 1 strip once the event has started.
  ///
  /// In fr, this message translates to:
  /// **'a commencé à {time}'**
  String myTicketStarted(String time);

  /// Screen 1 strip: time left until the event starts.
  ///
  /// In fr, this message translates to:
  /// **'{days, plural, =0{aujourd\'hui à {time}} =1{demain à {time}} other{dans {days} jours}}'**
  String myTicketWhen(int days, String time);
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
