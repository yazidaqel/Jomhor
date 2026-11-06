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
