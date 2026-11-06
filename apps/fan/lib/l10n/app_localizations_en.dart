// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Jomhor';

  @override
  String get navHome => 'Home';

  @override
  String get navTickets => 'Tickets';

  @override
  String get navProfile => 'Profile';

  @override
  String get placeholderComingSoon => 'Coming soon. We\'re getting it ready for you.';

  @override
  String get back => 'Retour';

  @override
  String get retry => 'Réessayer';

  @override
  String get loginTitle => 'Connecte-toi';

  @override
  String get loginPhoneIntro => 'Entre ton numéro de téléphone. On t\'envoie un code par SMS.';

  @override
  String get loginPhoneLabel => 'Numéro de téléphone';

  @override
  String get loginPhoneHint => '06 12 34 56 78';

  @override
  String get loginSendCode => 'Recevoir le code';

  @override
  String loginCodeIntro(String phone) {
    return 'Entre le code à 6 chiffres envoyé au $phone.';
  }

  @override
  String get loginCodeLabel => 'Code à 6 chiffres';

  @override
  String get loginVerify => 'Valider';

  @override
  String get loginChangePhone => 'Changer de numéro';

  @override
  String get loginResend => 'Renvoyer le code';

  @override
  String get errorPhoneInvalid => 'Ce numéro ne semble pas marocain. Essaie par exemple 06 12 34 56 78.';

  @override
  String get errorOtpInvalid => 'Code incorrect. Vérifie-le et réessaie.';

  @override
  String get errorOtpExpired => 'Ce code a expiré. Demande un nouveau code.';

  @override
  String get errorOtpRateLimited => 'Trop de demandes pour ce numéro. Patiente un peu avant de réessayer.';

  @override
  String get errorValidation => 'Vérifie les informations saisies.';

  @override
  String get errorNotFound => 'Introuvable. Il a peut-être été retiré.';

  @override
  String get errorNetwork => 'Pas de connexion. Vérifie ton réseau et réessaie.';

  @override
  String get errorMissingOrganizer => 'Application mal configurée : aucun club défini (ORGANIZER_ID).';

  @override
  String get errorGeneric => 'Un problème est survenu. Réessaie dans un instant.';

  @override
  String get heroLabelMatch => 'Prochain match · Domicile';

  @override
  String get heroLabelEvent => 'Prochain événement';

  @override
  String get versus => 'VS';

  @override
  String matchday(int n) {
    return 'Journée $n';
  }

  @override
  String whenWhere(String date, String time, String venue) {
    return '$date · $time · $venue';
  }

  @override
  String dotPair(String first, String second) {
    return '$first · $second';
  }

  @override
  String get countdownDays => 'Jours';

  @override
  String get countdownHours => 'Heures';

  @override
  String get countdownMinutes => 'Min';

  @override
  String ctaBuyFrom(String price) {
    return 'Prendre mes places · dès $price';
  }

  @override
  String get ctaBuy => 'Prendre mes places';

  @override
  String saleOpensOn(String date, String time) {
    return 'Vente le $date à $time';
  }

  @override
  String get saleOpening => 'Ouverture de la vente imminente';

  @override
  String get soldOut => 'Complet';

  @override
  String get saleClosed => 'Vente terminée';

  @override
  String get calendar => 'Calendrier';

  @override
  String chipFrom(String price) {
    return 'dès $price';
  }

  @override
  String get chipSoon => 'Bientôt';

  @override
  String get homeEmpty => 'Pas de match à venir pour l\'instant. Reviens bientôt.';

  @override
  String rulesOpen(int limit) {
    String _temp0 = intl.Intl.pluralLogic(limit, locale: localeName, other: '$limit billets', one: '1 billet');
    return 'Vente ouverte · limite $_temp0 par compte · billets nominatifs';
  }

  @override
  String rulesNotStarted(String date, String time, int limit) {
    String _temp0 = intl.Intl.pluralLogic(limit, locale: localeName, other: '$limit billets', one: '1 billet');
    return 'Vente le $date à $time · limite $_temp0 par compte · billets nominatifs';
  }

  @override
  String get rulesSoldOut => 'Complet · toutes les places sont vendues';

  @override
  String get rulesClosed => 'Vente terminée pour cet événement';

  @override
  String get zonesTitle => 'Tarifs par zone';

  @override
  String get zoneSeated => 'Places assises';

  @override
  String get zoneStanding => 'Debout';

  @override
  String zoneGates(int count, String codes) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: 'Portes $codes', one: 'Porte $codes');
    return '$_temp0';
  }

  @override
  String zoneRemaining(int count) {
    final intl.NumberFormat countNumberFormat = intl.NumberFormat.decimalPattern(localeName);
    final String countString = countNumberFormat.format(count);

    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: '$countString restants', one: '1 restant');
    return '$_temp0';
  }

  @override
  String get zoneLastSeats => 'Dernières places';

  @override
  String get paymentHint => 'Paiement par carte bancaire. Billet dans l\'app et sur WhatsApp.';

  @override
  String get footerFrom => 'À partir de';

  @override
  String get chooseZone => 'Choisir ma zone';
}
