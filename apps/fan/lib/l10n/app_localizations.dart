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

  /// Bottom nav, 3rd tab: screen 7.
  ///
  /// In fr, this message translates to:
  /// **'Club'**
  String get navClub;

  /// Bottom nav tab: the fan's profile.
  ///
  /// In fr, this message translates to:
  /// **'Profil'**
  String get navProfile;

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

  /// The example number (06 12 34 56 78), filled by the app: a left-to-right isolate in AR.
  ///
  /// In fr, this message translates to:
  /// **'Ce numéro ne semble pas marocain. Essaie par exemple {example}.'**
  String errorPhoneInvalid(String example);

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
  /// **'Prénom et nom en lettres latines ou arabes, sans mélanger les deux, de 2 à 60 caractères.'**
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

  /// The example number (06 12 34 56 78), filled by the app: a left-to-right isolate in AR.
  ///
  /// In fr, this message translates to:
  /// **'Numéro marocain invalide. Essaie {example}.'**
  String holderPhoneError(String example);

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

  /// No description provided for @methodWallet.
  ///
  /// In fr, this message translates to:
  /// **'Wallet mobile'**
  String get methodWallet;

  /// No description provided for @methodWalletHint.
  ///
  /// In fr, this message translates to:
  /// **'Paiement depuis ton wallet'**
  String get methodWalletHint;

  /// No description provided for @methodCash.
  ///
  /// In fr, this message translates to:
  /// **'Cash en agence'**
  String get methodCash;

  /// No description provided for @methodCashHint.
  ///
  /// In fr, this message translates to:
  /// **'Un code à payer au guichet du club ou en agence'**
  String get methodCashHint;

  /// Screen 4 cash row hint: the code's deadline, least(now + 24 h, sales close), venue time.
  ///
  /// In fr, this message translates to:
  /// **'Avant {deadline}'**
  String methodCashBefore(String deadline);

  /// Screen 4: CASH_AGENCY missing from payment_methods; the row is off.
  ///
  /// In fr, this message translates to:
  /// **'Trop tard pour le cash'**
  String get methodCashTooLate;

  /// No description provided for @payWalletCta.
  ///
  /// In fr, this message translates to:
  /// **'Payer {total} par wallet'**
  String payWalletCta(String total);

  /// No description provided for @payCashCta.
  ///
  /// In fr, this message translates to:
  /// **'Obtenir mon code cash'**
  String get payCashCta;

  /// 409 METHOD_NOT_AVAILABLE reason TOO_LATE.
  ///
  /// In fr, this message translates to:
  /// **'Trop tard pour payer en cash : la vente ferme bientôt. Choisis un autre moyen de paiement.'**
  String get errorCashTooLate;

  /// 409 METHOD_NOT_AVAILABLE reason CASH_CAP.
  ///
  /// In fr, this message translates to:
  /// **'Le paiement cash n\'est plus possible pour cet événement pour le moment. Choisis un autre moyen de paiement.'**
  String get errorCashCap;

  /// 409 METHOD_NOT_AVAILABLE reason NOT_CONFIGURED (or unknown).
  ///
  /// In fr, this message translates to:
  /// **'Ce moyen de paiement n\'est pas disponible. Choisis-en un autre.'**
  String get errorMethodNotConfigured;

  /// No description provided for @cashTitle.
  ///
  /// In fr, this message translates to:
  /// **'Ton code de paiement cash'**
  String get cashTitle;

  /// No description provided for @cashHoldBanner.
  ///
  /// In fr, this message translates to:
  /// **'Places réservées · paie avant {deadline}'**
  String cashHoldBanner(String deadline);

  /// No description provided for @cashCodeLabel.
  ///
  /// In fr, this message translates to:
  /// **'Code à présenter'**
  String get cashCodeLabel;

  /// No description provided for @cashExactAmount.
  ///
  /// In fr, this message translates to:
  /// **'Montant exact à régler : {amount}'**
  String cashExactAmount(String amount);

  /// No description provided for @cashCopy.
  ///
  /// In fr, this message translates to:
  /// **'Copier le code'**
  String get cashCopy;

  /// No description provided for @cashCopied.
  ///
  /// In fr, this message translates to:
  /// **'Code copié'**
  String get cashCopied;

  /// No description provided for @cashTimeLeft.
  ///
  /// In fr, this message translates to:
  /// **'Temps restant'**
  String get cashTimeLeft;

  /// No description provided for @cashWhereTitle.
  ///
  /// In fr, this message translates to:
  /// **'Où payer'**
  String get cashWhereTitle;

  /// No description provided for @cashNetworks.
  ///
  /// In fr, this message translates to:
  /// **'En agence : {names}'**
  String cashNetworks(String names);

  /// No description provided for @cashStepsTitle.
  ///
  /// In fr, this message translates to:
  /// **'Comment ça marche'**
  String get cashStepsTitle;

  /// No description provided for @cashStep1Title.
  ///
  /// In fr, this message translates to:
  /// **'Va au guichet ou en agence'**
  String get cashStep1Title;

  /// No description provided for @cashStep2Title.
  ///
  /// In fr, this message translates to:
  /// **'Donne le code et le montant exact'**
  String get cashStep2Title;

  /// No description provided for @cashStep3Title.
  ///
  /// In fr, this message translates to:
  /// **'Tes billets arrivent'**
  String get cashStep3Title;

  /// No description provided for @cashStep3Body.
  ///
  /// In fr, this message translates to:
  /// **'Dans l\'app et sur WhatsApp, dès que le paiement est enregistré.'**
  String get cashStep3Body;

  /// No description provided for @cashPayNowTitle.
  ///
  /// In fr, this message translates to:
  /// **'Tu préfères payer maintenant ?'**
  String get cashPayNowTitle;

  /// No description provided for @cashPayByCard.
  ///
  /// In fr, this message translates to:
  /// **'Payer par carte'**
  String get cashPayByCard;

  /// No description provided for @cashPayByWallet.
  ///
  /// In fr, this message translates to:
  /// **'Payer par wallet'**
  String get cashPayByWallet;

  /// No description provided for @cashHostedPending.
  ///
  /// In fr, this message translates to:
  /// **'Un paiement par carte ou wallet est en cours de confirmation. Ton code reste valable en attendant.'**
  String get cashHostedPending;

  /// No description provided for @cashStatus.
  ///
  /// In fr, this message translates to:
  /// **'En attente de paiement · cette page se met à jour toute seule.'**
  String get cashStatus;

  /// No description provided for @cashExpiredTitle.
  ///
  /// In fr, this message translates to:
  /// **'Code expiré · places libérées'**
  String get cashExpiredTitle;

  /// No description provided for @cashExpiredBody.
  ///
  /// In fr, this message translates to:
  /// **'Le délai pour payer ce code est passé. Tu peux refaire une commande s\'il reste des places.'**
  String get cashExpiredBody;

  /// No description provided for @backToEvent.
  ///
  /// In fr, this message translates to:
  /// **'Retour à l\'événement'**
  String get backToEvent;

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

  /// No description provided for @ticketWhatsAppHint.
  ///
  /// In fr, this message translates to:
  /// **'Reçois à nouveau le PDF de ce billet sur WhatsApp'**
  String get ticketWhatsAppHint;

  /// No description provided for @ticketWhatsAppSent.
  ///
  /// In fr, this message translates to:
  /// **'Billet renvoyé sur WhatsApp'**
  String get ticketWhatsAppSent;

  /// 429 DELIVERY_RATE_LIMITED: more than 3 re-sends of one ticket in 24 h.
  ///
  /// In fr, this message translates to:
  /// **'Ce billet a déjà été renvoyé 3 fois aujourd\'hui. Réessaie demain.'**
  String get ticketWhatsAppRateLimited;

  /// No description provided for @ticketWhatsAppNotValid.
  ///
  /// In fr, this message translates to:
  /// **'Ce billet n\'est plus valide : il ne peut pas être renvoyé.'**
  String get ticketWhatsAppNotValid;

  /// Re-send refused for another reason than the network (404, 401, 5xx).
  ///
  /// In fr, this message translates to:
  /// **'Ce billet ne peut pas être renvoyé pour le moment. Réessaie plus tard.'**
  String get ticketWhatsAppFailed;

  /// Screen 1 amber strip: an AWAITING_CASH order of an upcoming event.
  ///
  /// In fr, this message translates to:
  /// **'Commande en attente de paiement'**
  String get homeCashTitle;

  /// Screen 1 strip, under the event title. deadline = reserved_until in the venue zone.
  ///
  /// In fr, this message translates to:
  /// **'Code cash à payer avant {deadline}'**
  String homeCashDeadline(String deadline);

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

  /// No description provided for @cancel.
  ///
  /// In fr, this message translates to:
  /// **'Annuler'**
  String get cancel;

  /// No description provided for @save.
  ///
  /// In fr, this message translates to:
  /// **'Enregistrer'**
  String get save;

  /// No description provided for @profileName.
  ///
  /// In fr, this message translates to:
  /// **'Nom'**
  String get profileName;

  /// No description provided for @profileNameEmpty.
  ///
  /// In fr, this message translates to:
  /// **'Ajoute ton prénom et ton nom'**
  String get profileNameEmpty;

  /// No description provided for @profileEditName.
  ///
  /// In fr, this message translates to:
  /// **'Modifier le nom'**
  String get profileEditName;

  /// No description provided for @profilePhone.
  ///
  /// In fr, this message translates to:
  /// **'Téléphone'**
  String get profilePhone;

  /// No description provided for @profileLanguage.
  ///
  /// In fr, this message translates to:
  /// **'Langue'**
  String get profileLanguage;

  /// No description provided for @languageFr.
  ///
  /// In fr, this message translates to:
  /// **'Français'**
  String get languageFr;

  /// Language names are written in their own language, in every locale.
  ///
  /// In fr, this message translates to:
  /// **'العربية'**
  String get languageAr;

  /// No description provided for @languageEn.
  ///
  /// In fr, this message translates to:
  /// **'English'**
  String get languageEn;

  /// No description provided for @profileLogout.
  ///
  /// In fr, this message translates to:
  /// **'Se déconnecter'**
  String get profileLogout;

  /// No description provided for @profileLogoutAll.
  ///
  /// In fr, this message translates to:
  /// **'Se déconnecter de tous mes appareils'**
  String get profileLogoutAll;

  /// No description provided for @profileLogoutBody.
  ///
  /// In fr, this message translates to:
  /// **'Tes billets seront retirés de ce téléphone. Reconnecte-toi pour les retrouver.'**
  String get profileLogoutBody;

  /// No description provided for @profileLogoutAllBody.
  ///
  /// In fr, this message translates to:
  /// **'Ton compte sera déconnecté sur ce téléphone et sur tous tes autres appareils. Tes billets seront retirés de ce téléphone.'**
  String get profileLogoutAllBody;

  /// No description provided for @profileLogoutConfirm.
  ///
  /// In fr, this message translates to:
  /// **'Me déconnecter'**
  String get profileLogoutConfirm;

  /// No description provided for @profileLogoutAllFailed.
  ///
  /// In fr, this message translates to:
  /// **'Ce téléphone est déconnecté, mais tes autres appareils n\'ont pas pu l\'être (pas de réseau). Reconnecte-toi et réessaie.'**
  String get profileLogoutAllFailed;

  /// No description provided for @loading.
  ///
  /// In fr, this message translates to:
  /// **'Chargement'**
  String get loading;

  /// Accueil greeting with the fan first name (from display_name).
  ///
  /// In fr, this message translates to:
  /// **'Salut {name}'**
  String homeGreeting(String name);

  /// No description provided for @homeGreetingNoName.
  ///
  /// In fr, this message translates to:
  /// **'Salut'**
  String get homeGreetingNoName;

  /// No description provided for @homeGreetingGuest.
  ///
  /// In fr, this message translates to:
  /// **'Bienvenue'**
  String get homeGreetingGuest;

  /// No description provided for @homeEmptyTitle.
  ///
  /// In fr, this message translates to:
  /// **'Rien au programme'**
  String get homeEmptyTitle;

  /// No description provided for @homeOpenProfile.
  ///
  /// In fr, this message translates to:
  /// **'Mon profil'**
  String get homeOpenProfile;

  /// No description provided for @ticketsEmptyTitle.
  ///
  /// In fr, this message translates to:
  /// **'Pas encore de billet'**
  String get ticketsEmptyTitle;

  /// No description provided for @ticketOpen.
  ///
  /// In fr, this message translates to:
  /// **'Ouvrir le billet'**
  String get ticketOpen;

  /// design/BRAND.md tagline, under the large lockup of the login; never inside the lockup.
  ///
  /// In fr, this message translates to:
  /// **'Jomhor. Le 12e homme.'**
  String get brandTagline;

  /// Profil footer, under the lockup.
  ///
  /// In fr, this message translates to:
  /// **'Version {version}'**
  String profileVersion(String version);

  /// Screen 5 action: opens screen 8, the transfer sheet.
  ///
  /// In fr, this message translates to:
  /// **'Transférer'**
  String get transferAction;

  /// Screen 8 eyebrow, shown uppercase in FR and EN.
  ///
  /// In fr, this message translates to:
  /// **'Transférer ton billet'**
  String get transferEyebrow;

  /// No description provided for @transferTitle.
  ///
  /// In fr, this message translates to:
  /// **'À qui le donnes-tu ?'**
  String get transferTitle;

  /// when = transfer_until in the venue's zone.
  ///
  /// In fr, this message translates to:
  /// **'Possible jusqu\'au {when}'**
  String transferUntil(String when);

  /// No description provided for @transferPhone.
  ///
  /// In fr, this message translates to:
  /// **'Numéro WhatsApp'**
  String get transferPhone;

  /// No description provided for @transferPhoneHint.
  ///
  /// In fr, this message translates to:
  /// **'Le nouveau billet arrive sur ce numéro, dans l\'app et sur WhatsApp.'**
  String get transferPhoneHint;

  /// No description provided for @transferName.
  ///
  /// In fr, this message translates to:
  /// **'Nom et prénom'**
  String get transferName;

  /// No description provided for @transferNameHint.
  ///
  /// In fr, this message translates to:
  /// **'Le nouveau billet sera à ce nom.'**
  String get transferNameHint;

  /// No description provided for @transferNext.
  ///
  /// In fr, this message translates to:
  /// **'Continuer'**
  String get transferNext;

  /// No description provided for @transferConfirmTitle.
  ///
  /// In fr, this message translates to:
  /// **'Confirmer le transfert'**
  String get transferConfirmTitle;

  /// Confirm step: label of the recipient row.
  ///
  /// In fr, this message translates to:
  /// **'À'**
  String get transferTo;

  /// No description provided for @transferTicket.
  ///
  /// In fr, this message translates to:
  /// **'Billet'**
  String get transferTicket;

  /// No description provided for @transferWarning.
  ///
  /// In fr, this message translates to:
  /// **'Ton QR ne marchera plus. {name} reçoit un nouveau billet.'**
  String transferWarning(String name);

  /// No description provided for @transferFinal.
  ///
  /// In fr, this message translates to:
  /// **'Un transfert est définitif : tu ne pourras pas l\'annuler.'**
  String get transferFinal;

  /// No description provided for @transferEdit.
  ///
  /// In fr, this message translates to:
  /// **'Modifier'**
  String get transferEdit;

  /// Hold-to-confirm button: the transfer is sent once the fill is complete (800 ms).
  ///
  /// In fr, this message translates to:
  /// **'Maintiens pour transférer'**
  String get transferHold;

  /// Screen reader hint of the hold-to-confirm button.
  ///
  /// In fr, this message translates to:
  /// **'Garde le doigt appuyé jusqu\'à ce que la barre soit pleine.'**
  String get transferHoldHint;

  /// No description provided for @transferPending.
  ///
  /// In fr, this message translates to:
  /// **'Transfert en cours…'**
  String get transferPending;

  /// No description provided for @transferDone.
  ///
  /// In fr, this message translates to:
  /// **'{name} reçoit son billet dans l\'app et sur WhatsApp. Ton ancien QR ne marche plus.'**
  String transferDone(String name);

  /// 422 SELF on to_phone.
  ///
  /// In fr, this message translates to:
  /// **'C\'est ton numéro : ce billet est déjà à toi.'**
  String get transferSelf;

  /// No description provided for @transferNotValid.
  ///
  /// In fr, this message translates to:
  /// **'Ce billet n\'est plus valide : il ne peut pas être transféré.'**
  String get transferNotValid;

  /// 409 TICKET_NOT_VALID with reason ADMITTED.
  ///
  /// In fr, this message translates to:
  /// **'Ce billet est déjà passé à la porte : il ne peut plus être transféré.'**
  String get transferAdmitted;

  /// Network failure or 5xx: the transfer may have happened; the list is fetched again.
  ///
  /// In fr, this message translates to:
  /// **'On n\'a pas pu confirmer le transfert. Tes billets se rechargent : vérifie-les avant de réessayer.'**
  String get transferUnknown;

  /// No description provided for @transferClosed.
  ///
  /// In fr, this message translates to:
  /// **'Transferts fermés {lead} avant le début'**
  String transferClosed(String lead);

  /// No description provided for @transferUnavailable.
  ///
  /// In fr, this message translates to:
  /// **'Pas de transfert possible pour ce billet'**
  String get transferUnavailable;

  /// No description provided for @durationHours.
  ///
  /// In fr, this message translates to:
  /// **'{n} h'**
  String durationHours(int n);

  /// No description provided for @durationMinutes.
  ///
  /// In fr, this message translates to:
  /// **'{n} min'**
  String durationMinutes(int n);

  /// Billets: the muted row of a ticket given away.
  ///
  /// In fr, this message translates to:
  /// **'Transféré à {name} · {date}'**
  String transferRow(String name, String date);

  /// No description provided for @transferRowNoName.
  ///
  /// In fr, this message translates to:
  /// **'Transféré · {date}'**
  String transferRowNoName(String date);

  /// No description provided for @profileLostPhone.
  ///
  /// In fr, this message translates to:
  /// **'Téléphone perdu ?'**
  String get profileLostPhone;

  /// No description provided for @profileLostPhoneHint.
  ///
  /// In fr, this message translates to:
  /// **'Un nouveau QR pour tes billets à venir'**
  String get profileLostPhoneHint;

  /// No description provided for @lostPhoneBody.
  ///
  /// In fr, this message translates to:
  /// **'Tes billets à venir reçoivent un nouveau QR sur ce téléphone. Ceux du téléphone perdu ne passeront plus.'**
  String get lostPhoneBody;

  /// Re-issue revokes the caller's other sessions (docs/04-api.md reissue row).
  ///
  /// In fr, this message translates to:
  /// **'Le téléphone perdu sera déconnecté : il ne pourra pas recevoir tes nouveaux billets. Ce téléphone-ci reste connecté.'**
  String get lostPhoneSignedOut;

  /// No description provided for @lostPhoneNone.
  ///
  /// In fr, this message translates to:
  /// **'Aucun billet à venir ne peut recevoir de nouveau QR.'**
  String get lostPhoneNone;

  /// No description provided for @lostPhoneConfirm.
  ///
  /// In fr, this message translates to:
  /// **'Nouveaux QR'**
  String get lostPhoneConfirm;

  /// No description provided for @lostPhoneDone.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{Nouveau QR prêt pour 1 billet.} other{Nouveaux QR prêts pour {count} billets.}}'**
  String lostPhoneDone(int count);

  /// No description provided for @lostPhoneFailed.
  ///
  /// In fr, this message translates to:
  /// **'Certains billets n\'ont pas reçu de nouveau QR. Vérifie tes billets.'**
  String get lostPhoneFailed;

  /// Accueil chip (followed clubs) and Profil row → screen 11 picker.
  ///
  /// In fr, this message translates to:
  /// **'Mes clubs'**
  String get myClubs;

  /// Accueil chip: events of every listed club.
  ///
  /// In fr, this message translates to:
  /// **'Tout'**
  String get chipAll;

  /// Screen 11 (club picker) title.
  ///
  /// In fr, this message translates to:
  /// **'Tes clubs'**
  String get clubsTitle;

  /// No description provided for @clubsIntro.
  ///
  /// In fr, this message translates to:
  /// **'Suis tes clubs pour voir leurs matchs en premier. Tu changes quand tu veux.'**
  String get clubsIntro;

  /// Picker search field, shown above 8 clubs.
  ///
  /// In fr, this message translates to:
  /// **'Chercher un club'**
  String get clubsSearch;

  /// No description provided for @clubsNoResult.
  ///
  /// In fr, this message translates to:
  /// **'Aucun club ne correspond.'**
  String get clubsNoResult;

  /// Outlined chip on a club card; text button on an Accueil card club line.
  ///
  /// In fr, this message translates to:
  /// **'Suivre'**
  String get clubFollow;

  /// Accent chip with a check: the fan follows this club.
  ///
  /// In fr, this message translates to:
  /// **'Suivi'**
  String get clubFollowing;

  /// Picker on first launch: primary CTA, enabled from one follow.
  ///
  /// In fr, this message translates to:
  /// **'Continuer'**
  String get clubsContinue;

  /// Picker on first launch: skip, Accueil opens on Tout.
  ///
  /// In fr, this message translates to:
  /// **'Tout voir'**
  String get clubsSeeAll;

  /// Picker opened from Profil: closes it.
  ///
  /// In fr, this message translates to:
  /// **'Terminé'**
  String get clubsDone;

  /// Caption under a club name in the picker (organizers.kind).
  ///
  /// In fr, this message translates to:
  /// **'{kind, select, SPORTS_CLUB{Club sportif} PROMOTER{Organisateur} VENUE{Salle} other{Organisateur}}'**
  String clubKind(String kind);

  /// Accueil, a club chip with no event, the club(s) being sports clubs.
  ///
  /// In fr, this message translates to:
  /// **'Pas de match à venir'**
  String get homeNoMatch;

  /// Accueil, a club chip with no event, any other kind of organizer.
  ///
  /// In fr, this message translates to:
  /// **'Pas d\'événement à venir'**
  String get homeNoEvent;

  /// Accueil empty club chip → the Tout chip.
  ///
  /// In fr, this message translates to:
  /// **'Voir tout'**
  String get homeSeeAll;

  /// Screen 5 gate row chip: flips the ticket to its gate side.
  ///
  /// In fr, this message translates to:
  /// **'Itinéraire'**
  String get gateRoute;

  /// Gate side: opens the gate location in the phone's maps app.
  ///
  /// In fr, this message translates to:
  /// **'Ouvrir dans Plans'**
  String get gateOpenMaps;

  /// Gate side: flips back to the QR.
  ///
  /// In fr, this message translates to:
  /// **'Retourner le billet'**
  String get gateFlipBack;

  /// Gate side: the zone's other gates.
  ///
  /// In fr, this message translates to:
  /// **'Autres portes de ta zone'**
  String get gateOthers;

  /// Gate load chip, LOW.
  ///
  /// In fr, this message translates to:
  /// **'Fluide'**
  String get gateLevelLow;

  /// Gate load chip, MEDIUM.
  ///
  /// In fr, this message translates to:
  /// **'Modérée'**
  String get gateLevelMedium;

  /// Gate load chip, HIGH.
  ///
  /// In fr, this message translates to:
  /// **'Chargée'**
  String get gateLevelHigh;

  /// Your gate is HIGH and this gate of the zone is LOW.
  ///
  /// In fr, this message translates to:
  /// **'Porte {code} plus fluide en ce moment'**
  String gateSmoother(String code);

  /// Screen reader label of the PORTE fact, which flips the card.
  ///
  /// In fr, this message translates to:
  /// **'{gates}, voir l\'itinéraire'**
  String gateShow(String gates);

  /// Screen reader label of the gate side.
  ///
  /// In fr, this message translates to:
  /// **'Face porte du billet, porte {code}'**
  String gateSideLabel(String code);

  /// Screen 5 once the ticket was scanned at a gate.
  ///
  /// In fr, this message translates to:
  /// **'Tu es dedans'**
  String get ticketEntered;

  /// Time in the venue zone and gate of the admitting scan.
  ///
  /// In fr, this message translates to:
  /// **'Entré à {time} · Porte {code}'**
  String ticketEnteredAt(String time, String code);

  /// Screen 5 PORTE fact: how many other gates serve the zone.
  ///
  /// In fr, this message translates to:
  /// **'+{count}'**
  String gateMore(int count);

  /// Screen 7 title; count = followed clubs.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{Ton club} other{Tes clubs}}'**
  String clubTabTitle(int count);

  /// Screen 7 without a followed club.
  ///
  /// In fr, this message translates to:
  /// **'Choisis tes clubs'**
  String get clubNoFollowsTitle;

  /// No description provided for @clubNoFollows.
  ///
  /// In fr, this message translates to:
  /// **'Suis un club pour avoir ta carte de membre et ta saison au stade.'**
  String get clubNoFollows;

  /// → screen 11.
  ///
  /// In fr, this message translates to:
  /// **'Choisir mes clubs'**
  String get clubNoFollowsAction;

  /// Overline of the member card band.
  ///
  /// In fr, this message translates to:
  /// **'Carte membre'**
  String get clubMemberCard;

  /// Label over the member number (uppercased in FR/EN).
  ///
  /// In fr, this message translates to:
  /// **'Membre N°'**
  String get clubMemberNo;

  /// Join month on the card, e.g. depuis nov. 2026.
  ///
  /// In fr, this message translates to:
  /// **'depuis {date}'**
  String clubMemberSince(String date);

  /// Non-member card CTA → join sheet.
  ///
  /// In fr, this message translates to:
  /// **'Rejoindre le club · gratuit'**
  String get clubJoin;

  /// No description provided for @clubJoinPitch.
  ///
  /// In fr, this message translates to:
  /// **'Ta carte de membre, ta saison au stade et les nouvelles du club.'**
  String get clubJoinPitch;

  /// Join sheet title.
  ///
  /// In fr, this message translates to:
  /// **'Rejoindre {club}'**
  String clubJoinTitle(String club);

  /// No description provided for @clubJoinBody.
  ///
  /// In fr, this message translates to:
  /// **'C\'est gratuit. Tu choisis ce que le club peut t\'envoyer et tu changes quand tu veux.'**
  String get clubJoinBody;

  /// Join sheet CTA: PUT /fan/me/membership.
  ///
  /// In fr, this message translates to:
  /// **'Rejoindre'**
  String get clubJoinConfirm;

  /// Opt-in switch (marketing_push), default off.
  ///
  /// In fr, this message translates to:
  /// **'Notifications du club'**
  String get clubPushTitle;

  /// No description provided for @clubPushBody.
  ///
  /// In fr, this message translates to:
  /// **'Matchs, billetterie et nouvelles du club, sur ce téléphone.'**
  String get clubPushBody;

  /// Opt-in switch (marketing_whatsapp), default off.
  ///
  /// In fr, this message translates to:
  /// **'Messages WhatsApp du club'**
  String get clubWhatsappTitle;

  /// No description provided for @clubWhatsappBody.
  ///
  /// In fr, this message translates to:
  /// **'Les nouvelles du club sur ton WhatsApp.'**
  String get clubWhatsappBody;

  /// Section over the 12-dot row.
  ///
  /// In fr, this message translates to:
  /// **'Ta saison'**
  String get clubSeason;

  /// Caption under the 12th (amber) dot.
  ///
  /// In fr, this message translates to:
  /// **'Prochain · {date}'**
  String clubSeasonNext(String date);

  /// Screen reader label of the 12-dot row.
  ///
  /// In fr, this message translates to:
  /// **'{attended} sur {total}'**
  String clubSeasonLabel(int attended, int total);

  /// Screen reader, after the count.
  ///
  /// In fr, this message translates to:
  /// **'prochain : {title}'**
  String clubSeasonLabelNext(String title);

  /// events_attended, the club's past events all being football matches (lot-4 Q92).
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =0{Aucun match au stade} =1{1 match au stade} other{{count} matchs au stade}}'**
  String clubMatchesAttended(int count);

  /// events_attended, any other club (lot-4 Q92).
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =0{Aucun événement sur place} =1{1 événement sur place} other{{count} événements sur place}}'**
  String clubEventsAttended(int count);

  /// Tier progress; hidden at the top tier.
  ///
  /// In fr, this message translates to:
  /// **'Encore {count} pour {tier}'**
  String clubToNextTier(int count, String tier);

  /// The 3 newest closed events.
  ///
  /// In fr, this message translates to:
  /// **'Derniers'**
  String get clubRecent;

  /// Chip on a past event the fan attended.
  ///
  /// In fr, this message translates to:
  /// **'J\'y étais'**
  String get clubAttended;

  /// Chip on a past event the fan did not attend.
  ///
  /// In fr, this message translates to:
  /// **'Manqué'**
  String get clubMissed;

  /// Member settings section: the two opt-ins.
  ///
  /// In fr, this message translates to:
  /// **'Messages du club'**
  String get clubMessages;

  /// No description provided for @clubLeave.
  ///
  /// In fr, this message translates to:
  /// **'Quitter le club'**
  String get clubLeave;

  /// Leave confirm dialog.
  ///
  /// In fr, this message translates to:
  /// **'Quitter {club} ?'**
  String clubLeaveTitle(String club);

  /// No description provided for @clubLeaveBody.
  ///
  /// In fr, this message translates to:
  /// **'Tu ne recevras plus les messages du club. Si tu reviens, tu retrouves ton numéro.'**
  String get clubLeaveBody;

  /// No description provided for @clubLeaveConfirm.
  ///
  /// In fr, this message translates to:
  /// **'Quitter'**
  String get clubLeaveConfirm;

  /// Accueil member strip: the member number (LTR isolate in AR).
  ///
  /// In fr, this message translates to:
  /// **'N° {number}'**
  String memberStripNo(String number);

  /// Accueil member strip, football club (lot-4 Q92).
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{1 match} other{{count} matchs}}'**
  String memberStripMatches(int count);

  /// Accueil member strip, other clubs (lot-4 Q92).
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{1 événement} other{{count} événements}}'**
  String memberStripEvents(int count);

  /// Screen 4 switch on a friend row with a phone (split pay), default off.
  ///
  /// In fr, this message translates to:
  /// **'Paie sa place'**
  String get splitPays;

  /// Screen 4 summary when friends pay their own place.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{Tu paies 1 place · {total}} other{Tu paies {count} places · {total}}}'**
  String splitYouPay(int count, String total);

  /// Screen 4 summary: places friends pay themselves.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{1 place payée par tes amis} other{{count} places payées par tes amis}}'**
  String splitFriendsPay(int count);

  /// Screen 4 caption; literal 2 h (lot-4 Q109).
  ///
  /// In fr, this message translates to:
  /// **'Tes amis ont 2 h après ton paiement'**
  String get splitCaption;

  /// Switch disabled after 422 TOO_LATE.
  ///
  /// In fr, this message translates to:
  /// **'Trop tard pour partager'**
  String get splitTooLate;

  /// 422 PHONE_REQUIRED: the friend's phone is the buyer's.
  ///
  /// In fr, this message translates to:
  /// **'Ce numéro est le tien'**
  String get holderOwnPhone;

  /// 409 PER_ACCOUNT_LIMIT on a friend who pays.
  ///
  /// In fr, this message translates to:
  /// **'{label} a déjà atteint sa limite'**
  String holderLimit(String label);

  /// Screen 9 initiator header, before the event title.
  ///
  /// In fr, this message translates to:
  /// **'Ta bande'**
  String get groupBand;

  /// Screen 9 big figure, read by screen readers (places, lot-4 Q107).
  ///
  /// In fr, this message translates to:
  /// **'{paid}/{total} payées'**
  String groupPaid(int paid, int total);

  /// Screen 9 word after the big {paid}/{total} figure.
  ///
  /// In fr, this message translates to:
  /// **'payées'**
  String get groupPaidWord;

  /// Member chip: unpaid, deadline in the venue zone.
  ///
  /// In fr, this message translates to:
  /// **'À payer · avant {time}'**
  String groupDue(String time);

  /// Member chip: paid.
  ///
  /// In fr, this message translates to:
  /// **'Payé'**
  String get groupPaidChip;

  /// Member chip: released unpaid.
  ///
  /// In fr, this message translates to:
  /// **'Expiré'**
  String get groupExpiredChip;

  /// Per unpaid member: opens WhatsApp with the copy and pay link.
  ///
  /// In fr, this message translates to:
  /// **'Relancer sur WhatsApp'**
  String get groupRemind;

  /// Per unpaid member: copies the pay link.
  ///
  /// In fr, this message translates to:
  /// **'Copier le lien'**
  String get groupCopyLink;

  /// Snackbar after copying a pay link.
  ///
  /// In fr, this message translates to:
  /// **'Lien copié'**
  String get groupLinkCopied;

  /// WhatsApp message prefilled by Relancer; no phone, no name.
  ///
  /// In fr, this message translates to:
  /// **'Je t\'ai gardé une place pour {event}. Paie-la avant {time} :\n{url}'**
  String groupRemindText(String event, String time, String url);

  /// Screen 9 member headline.
  ///
  /// In fr, this message translates to:
  /// **'{label} t\'a gardé une place'**
  String memberKept(String label);

  /// Screen 9 member headline when initiator_label is null.
  ///
  /// In fr, this message translates to:
  /// **'Un ami t\'a gardé une place'**
  String get memberKeptAnon;

  /// Screen 9 member: the hold ran out.
  ///
  /// In fr, this message translates to:
  /// **'La place a été libérée'**
  String get memberExpired;

  /// Accueil amber strip: a place a friend kept, to pay.
  ///
  /// In fr, this message translates to:
  /// **'Ta place t\'attend · avant {time}'**
  String homeMemberTitle(String time);

  /// Screen 10 title.
  ///
  /// In fr, this message translates to:
  /// **'Notifications'**
  String get inboxTitle;

  /// Accueil bell tooltip and screen-reader label.
  ///
  /// In fr, this message translates to:
  /// **'Notifications'**
  String get inboxBell;

  /// Bell tooltip while the accent dot shows (a message newer than the last visit).
  ///
  /// In fr, this message translates to:
  /// **'Notifications · nouveaux messages'**
  String get inboxBellUnread;

  /// Screen 10 empty state.
  ///
  /// In fr, this message translates to:
  /// **'Rien pour l\'instant'**
  String get inboxEmpty;

  /// Inbox row time, under a minute ago.
  ///
  /// In fr, this message translates to:
  /// **'À l\'instant'**
  String get inboxJustNow;

  /// Inbox row time.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{il y a 1 min} other{il y a {count} min}}'**
  String inboxMinutesAgo(int count);

  /// Inbox row time.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{il y a 1 h} other{il y a {count} h}}'**
  String inboxHoursAgo(int count);

  /// Inbox row time, in whole days.
  ///
  /// In fr, this message translates to:
  /// **'{count, plural, =1{hier} other{il y a {count} j}}'**
  String inboxDaysAgo(int count);

  /// Profil row: the phone's push permission (lot-4 Q123).
  ///
  /// In fr, this message translates to:
  /// **'Notifications'**
  String get profilePushTitle;

  /// Profil row subtitle before the permission is asked.
  ///
  /// In fr, this message translates to:
  /// **'Billets reçus, paiements et messages de tes clubs'**
  String get profilePushBody;

  /// Profil row subtitle: permission granted.
  ///
  /// In fr, this message translates to:
  /// **'Activées'**
  String get profilePushOn;

  /// Profil row subtitle: permission refused; only the OS settings can turn it on.
  ///
  /// In fr, this message translates to:
  /// **'Active-les dans les réglages du téléphone'**
  String get profilePushDenied;

  /// Profil row button: asks for the push permission.
  ///
  /// In fr, this message translates to:
  /// **'Activer'**
  String get profilePushEnable;

  /// Screens 1 and 2 CTA (disabled) while the room is SCHEDULED: opens_at in the venue zone.
  ///
  /// In fr, this message translates to:
  /// **'File d\'attente dès {time}'**
  String queueOpensAt(String time);

  /// Under the SCHEDULED CTA when signed out.
  ///
  /// In fr, this message translates to:
  /// **'Connecte-toi maintenant'**
  String get queueLoginNow;

  /// CTA while the room takes joins.
  ///
  /// In fr, this message translates to:
  /// **'Rejoindre la file'**
  String get queueJoin;

  /// CTA when the phone holds a queue token for the event.
  ///
  /// In fr, this message translates to:
  /// **'Reprendre ma place'**
  String get queueResume;

  /// Calendar chip: the room is open.
  ///
  /// In fr, this message translates to:
  /// **'File d\'attente'**
  String get chipQueue;

  /// Screen 2 banner for an event with a room.
  ///
  /// In fr, this message translates to:
  /// **'Vente à forte demande · file d\'attente'**
  String get roomHighDemand;

  /// Screen 2: the room's rule, only the parts set, joined by ' · '.
  ///
  /// In fr, this message translates to:
  /// **'Réservée aux fans vérifiés: {rules}'**
  String roomEligibility(String rules);

  /// No description provided for @roomVerifiedBefore.
  ///
  /// In fr, this message translates to:
  /// **'compte vérifié avant le {date}'**
  String roomVerifiedBefore(String date);

  /// No description provided for @roomMemberBefore.
  ///
  /// In fr, this message translates to:
  /// **'membre depuis le {date}'**
  String roomMemberBefore(String date);

  /// No description provided for @roomMinEvents.
  ///
  /// In fr, this message translates to:
  /// **'{n, plural, =1{1 match au stade} other{{n} matchs au stade}}'**
  String roomMinEvents(int n);

  /// Screen 12 name (screen readers).
  ///
  /// In fr, this message translates to:
  /// **'File d\'attente'**
  String get queueTitle;

  /// Toast when leaving screen 12 with a place.
  ///
  /// In fr, this message translates to:
  /// **'Ta place est gardée'**
  String get queuePlaceKept;

  /// No description provided for @queueJoining.
  ///
  /// In fr, this message translates to:
  /// **'Connexion à la file…'**
  String get queueJoining;

  /// Screen 12 pre-queue label (uppercased in FR/EN).
  ///
  /// In fr, this message translates to:
  /// **'Tirage au sort à {time}'**
  String queueDrawAt(String time);

  /// No description provided for @queueDrawText.
  ///
  /// In fr, this message translates to:
  /// **'Tous ceux qui sont là avant {time} ont la même chance: l\'ordre est tiré au sort. Après, premier arrivé, premier servi.'**
  String queueDrawText(String time);

  /// No description provided for @queueInRoom.
  ///
  /// In fr, this message translates to:
  /// **'{n, plural, =1{1 fan dans la file} other{{n} fans dans la file}}'**
  String queueInRoom(int n);

  /// Countdown tile caption.
  ///
  /// In fr, this message translates to:
  /// **'Sec'**
  String get queueSeconds;

  /// After the draw, before the fan's rank is known.
  ///
  /// In fr, this message translates to:
  /// **'Placement en cours'**
  String get queueRankingTitle;

  /// No description provided for @queueRanking.
  ///
  /// In fr, this message translates to:
  /// **'Ta position arrive dans quelques secondes.'**
  String get queueRanking;

  /// No description provided for @queuePositionLabel.
  ///
  /// In fr, this message translates to:
  /// **'Ta position'**
  String get queuePositionLabel;

  /// The fan's position, display 88.
  ///
  /// In fr, this message translates to:
  /// **'N° {n}'**
  String queuePosition(int n);

  /// No description provided for @queueAhead.
  ///
  /// In fr, this message translates to:
  /// **'{n, plural, =0{Personne devant toi} =1{1 fan devant toi} other{{n} fans devant toi}}'**
  String queueAhead(int n);

  /// No description provided for @queueWait.
  ///
  /// In fr, this message translates to:
  /// **'Attente estimée · {time}'**
  String queueWait(String time);

  /// No description provided for @queueWaitLess.
  ///
  /// In fr, this message translates to:
  /// **'moins d\'une minute'**
  String get queueWaitLess;

  /// No description provided for @queueWaitMin.
  ///
  /// In fr, this message translates to:
  /// **'environ {n} min'**
  String queueWaitMin(int n);

  /// No description provided for @queueWaitHour.
  ///
  /// In fr, this message translates to:
  /// **'plus d\'une heure'**
  String get queueWaitHour;

  /// No description provided for @queuePaused.
  ///
  /// In fr, this message translates to:
  /// **'Admission en pause · ta place est gardée'**
  String get queuePaused;

  /// No description provided for @queueKeepPlace.
  ///
  /// In fr, this message translates to:
  /// **'Tu peux fermer l\'app: ta place est gardée'**
  String get queueKeepPlace;

  /// No description provided for @queueLimit.
  ///
  /// In fr, this message translates to:
  /// **'Limite {n, plural, =1{1 billet} other{{n} billets}} par compte · billets nominatifs'**
  String queueLimit(int n);

  /// No description provided for @queueRemaining.
  ///
  /// In fr, this message translates to:
  /// **'{n, plural, =1{Il reste 1 place} other{Il reste {n} places}}'**
  String queueRemaining(int n);

  /// No description provided for @queueSoldOut.
  ///
  /// In fr, this message translates to:
  /// **'Tout est réservé pour l\'instant · des places peuvent se libérer'**
  String get queueSoldOut;

  /// Chip on screen 12 when the event has a rule (the fan passed it to join).
  ///
  /// In fr, this message translates to:
  /// **'Fan vérifié'**
  String get queueVerified;

  /// Uppercased in FR/EN.
  ///
  /// In fr, this message translates to:
  /// **'C\'est ton tour'**
  String get queueTurnTitle;

  /// mm:ss to admission.expires_at.
  ///
  /// In fr, this message translates to:
  /// **'Tu as {time} pour réserver'**
  String queueTurnLeft(String time);

  /// No description provided for @queueChoose.
  ///
  /// In fr, this message translates to:
  /// **'Choisir mes places'**
  String get queueChoose;

  /// No description provided for @queueExpiredTitle.
  ///
  /// In fr, this message translates to:
  /// **'Ton tour est passé'**
  String get queueExpiredTitle;

  /// No description provided for @queueExpiredBody.
  ///
  /// In fr, this message translates to:
  /// **'Une nouvelle place te remet en fin de file.'**
  String get queueExpiredBody;

  /// No description provided for @queueRejoin.
  ///
  /// In fr, this message translates to:
  /// **'Reprendre une place'**
  String get queueRejoin;

  /// No description provided for @queueOffline.
  ///
  /// In fr, this message translates to:
  /// **'Hors ligne · ta place est gardée'**
  String get queueOffline;

  /// Screen-reader label of the twelve dots.
  ///
  /// In fr, this message translates to:
  /// **'Progression dans la file'**
  String get queueProgress;

  /// Screen-reader value of the twelve dots: how many are lit.
  ///
  /// In fr, this message translates to:
  /// **'{lit} sur 12'**
  String queueProgressValue(int lit);

  /// No description provided for @queueNotEligibleTitle.
  ///
  /// In fr, this message translates to:
  /// **'Vente réservée aux fans vérifiés'**
  String get queueNotEligibleTitle;

  /// No description provided for @queueAccountTooNew.
  ///
  /// In fr, this message translates to:
  /// **'Compte vérifié après le {date}'**
  String queueAccountTooNew(String date);

  /// No description provided for @queueNotMember.
  ///
  /// In fr, this message translates to:
  /// **'Réservée aux membres inscrits avant le {date}'**
  String queueNotMember(String date);

  /// No description provided for @queueNotEnoughEvents.
  ///
  /// In fr, this message translates to:
  /// **'{n, plural, =1{Il faut 1 match au stade · tu en as {attended}} other{Il faut {n} matchs au stade · tu en as {attended}}}'**
  String queueNotEnoughEvents(int n, int attended);

  /// No description provided for @queueDeviceLimit.
  ///
  /// In fr, this message translates to:
  /// **'Trop de comptes sur ce téléphone pour cette vente'**
  String get queueDeviceLimit;

  /// No description provided for @queueRateLimited.
  ///
  /// In fr, this message translates to:
  /// **'Beaucoup de fans sur ce réseau · nouvel essai dans {s} s'**
  String queueRateLimited(int s);

  /// No description provided for @queueRateLimitedAgain.
  ///
  /// In fr, this message translates to:
  /// **'Beaucoup de fans sur ce réseau. Réessaie dans un instant.'**
  String get queueRateLimitedAgain;

  /// No description provided for @queueNotOpenTitle.
  ///
  /// In fr, this message translates to:
  /// **'La file n\'est pas encore ouverte'**
  String get queueNotOpenTitle;

  /// No description provided for @queueNotOpen.
  ///
  /// In fr, this message translates to:
  /// **'Garde cet écran ouvert: tu entres dans la file automatiquement.'**
  String get queueNotOpen;

  /// No description provided for @queueClosed.
  ///
  /// In fr, this message translates to:
  /// **'La vente est terminée pour cet événement.'**
  String get queueClosed;

  /// Screens 3 and 4 slim bar while admitted: mm:ss left.
  ///
  /// In fr, this message translates to:
  /// **'Ton tour · {time}'**
  String turnBar(String time);
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
