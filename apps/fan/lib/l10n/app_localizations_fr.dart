// ignore: unused_import
import 'package:intl/intl.dart' as intl;

import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for French (`fr`).
class AppLocalizationsFr extends AppLocalizations {
  AppLocalizationsFr([String locale = 'fr']) : super(locale);

  @override
  String get appTitle => 'Jomhor';

  @override
  String get navHome => 'Accueil';

  @override
  String get navTickets => 'Billets';

  @override
  String get navProfile => 'Profil';

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

  @override
  String zonesSubtitle(String event, String date) {
    return '$event · $date';
  }

  @override
  String get pickZoneHint => 'Touche une zone pour choisir tes places.';

  @override
  String get pickedZone => 'Zone choisie';

  @override
  String placesMax(int max) {
    return 'Billets · max $max';
  }

  @override
  String get fewerPlaces => 'Un billet de moins';

  @override
  String get morePlaces => 'Un billet de plus';

  @override
  String get groupHint => 'Les places d\'un même achat sont côte à côte. Chaque ami reçoit son propre QR.';

  @override
  String get total => 'Total';

  @override
  String get continueCta => 'Continuer';

  @override
  String get allSoldOut => 'Complet · toutes les zones sont vendues';

  @override
  String get payTitle => 'Paiement';

  @override
  String holdLeft(String time) {
    return 'Places réservées · $time';
  }

  @override
  String summaryLine(int qty, String zone) {
    return '$qty × $zone';
  }

  @override
  String get serviceFee => 'Frais de service';

  @override
  String get holdersTitle => 'Billets nominatifs';

  @override
  String get holderMe => 'Billet 1 · Moi';

  @override
  String holderN(int n) {
    return 'Billet $n';
  }

  @override
  String get holderNameLabel => 'Prénom et nom';

  @override
  String get holderNameError => 'Prénom et nom en lettres latines, de 2 à 60 caractères.';

  @override
  String get holderPhoneLabel => 'WhatsApp de ton ami (facultatif)';

  @override
  String get holderPhoneHelp =>
      'Il reçoit son billet sur WhatsApp et dans l\'app. Sans numéro, le billet reste chez toi.';

  @override
  String get holderPhoneError => 'Numéro marocain invalide. Essaie 06 12 34 56 78.';

  @override
  String get methodTitle => 'Moyen de paiement';

  @override
  String get methodCard => 'Carte bancaire';

  @override
  String get methodCardHint => 'Visa, Mastercard · 3D Secure';

  @override
  String get instant => 'Instantané';

  @override
  String payCta(String total) {
    return 'Payer $total';
  }

  @override
  String get payCaption => 'Billets envoyés dans l\'app et sur WhatsApp';

  @override
  String get friendsConsent => 'Je confirme que ces personnes acceptent de recevoir leur billet sur WhatsApp';

  @override
  String get friendsConsentMissing => 'Coche la case pour confirmer que tes amis sont d\'accord.';

  @override
  String get checkPayment => 'Voir l\'état du paiement';

  @override
  String busyRetry(int s) {
    return 'Beaucoup de monde en ce moment · nouvel essai dans $s s';
  }

  @override
  String get expiredTitle => 'Réservation expirée';

  @override
  String get expiredBody => 'Tes places ont été libérées. Choisis-les à nouveau.';

  @override
  String get expiredCta => 'Choisir à nouveau';

  @override
  String get cardPageTitle => 'Paiement sécurisé';

  @override
  String get orderPending => 'Paiement en cours de confirmation.';

  @override
  String get orderPendingHint =>
      'Ça prend quelques secondes. Ne paie pas une deuxième fois : la banque peut confirmer un peu plus tard.';

  @override
  String get orderPaymentFailed =>
      'Ton paiement n\'a pas abouti. Tu peux réessayer tant que tes places sont réservées.';

  @override
  String get orderAwaitingPayment => 'Commande en attente de paiement.';

  @override
  String get orderNotPaidYet =>
      'Aucun paiement n\'a encore été fait pour cette commande. Tu peux payer tant que tes places sont réservées.';

  @override
  String get orderPayAgainCaution =>
      'Toujours pas de confirmation. Réessaie seulement si ta carte a été refusée et que rien n\'a été débité.';

  @override
  String get payAgain => 'Payer à nouveau';

  @override
  String get orderPaidTitle => 'C\'est validé !';

  @override
  String get orderPaidBody => 'Tes billets sont dans l\'onglet Billets. Tes amis reçoivent les leurs sur WhatsApp.';

  @override
  String get seeTickets => 'Voir mes billets';

  @override
  String get orderExpired => 'Cette réservation a expiré.';

  @override
  String get backHome => 'Retour à l\'accueil';

  @override
  String get errorNotStarted => 'La vente n\'a pas encore commencé.';

  @override
  String get errorSaleClosed => 'La vente est terminée pour cet événement.';

  @override
  String get errorNotOnSale => 'Cet événement n\'est pas en vente.';

  @override
  String get errorSoldOut => 'Plus de places dans ce tarif. Choisis une autre zone.';

  @override
  String errorPerAccountLimit(int limit, int held) {
    return 'Limite atteinte : $limit billets par compte pour cet événement, tu en as déjà $held.';
  }

  @override
  String get errorOrderExpired => 'Ta réservation a expiré. Choisis à nouveau tes places.';

  @override
  String get errorOrderNotPayable => 'Cette commande ne peut plus être payée.';

  @override
  String get close => 'Fermer';

  @override
  String get ticketsTitle => 'Mes billets';

  @override
  String get ticketsUpcoming => 'À venir';

  @override
  String get ticketsPast => 'Passés';

  @override
  String get ticketsEmpty => 'Aucun billet pour l\'instant. Tes billets arrivent ici dès que le paiement est confirmé.';

  @override
  String get ticketsBrowse => 'Voir les matchs';

  @override
  String get ticketsOffline => 'Hors ligne · billets enregistrés sur ton téléphone';

  @override
  String ticketRowFacts(String zone, String ticketNo) {
    return '$zone · N° $ticketNo';
  }

  @override
  String get ticketMatchday => 'Mode jour de match';

  @override
  String get ticketBrightness => 'Luminosité au maximum';

  @override
  String get ticketZone => 'Zone';

  @override
  String ticketGates(int count) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: 'Portes', one: 'Porte');
    return '$_temp0';
  }

  @override
  String ticketGatesList(int count, String codes) {
    String _temp0 = intl.Intl.pluralLogic(count, locale: localeName, other: 'Portes $codes', one: 'Porte $codes');
    return '$_temp0';
  }

  @override
  String get listSeparator => ', ';

  @override
  String get ticketKickoff => 'Coup d\'envoi';

  @override
  String get ticketStart => 'Début';

  @override
  String get ticketHolder => 'Titulaire';

  @override
  String get ticketNumber => 'N° billet';

  @override
  String get ticketSeat => 'Place';

  @override
  String ticketNextCode(int s) {
    return 'Nouveau code dans $s s';
  }

  @override
  String get ticketWorksOffline => 'Fonctionne sans réseau';

  @override
  String ticketQrLabel(String ticketNo) {
    return 'Code QR du billet $ticketNo';
  }

  @override
  String get ticketClosed => 'Événement terminé';

  @override
  String get ticketCancelled => 'Billet annulé';

  @override
  String get ticketRefunded => 'Billet remboursé';

  @override
  String get ticketBlocked => 'Billet bloqué. Contacte le club.';

  @override
  String get ticketNotValid => 'Billet non valide';

  @override
  String get ticketNotFound => 'Ce billet n\'est pas sur ton téléphone.';

  @override
  String get ticketWhatsApp => 'WhatsApp';

  @override
  String get ticketWhatsAppHint => 'Reçois à nouveau le PDF de ce billet sur WhatsApp';

  @override
  String get ticketWhatsAppSent => 'Billet renvoyé sur WhatsApp';

  @override
  String get ticketWhatsAppRateLimited => 'Ce billet a déjà été renvoyé 3 fois aujourd\'hui. Réessaie demain.';

  @override
  String get ticketWhatsAppNotValid => 'Ce billet n\'est plus valide : il ne peut pas être renvoyé.';

  @override
  String get ticketWhatsAppFailed => 'Ce billet ne peut pas être renvoyé pour le moment. Réessaie plus tard.';

  @override
  String myTicketTitle(String what, String date) {
    return 'Mon billet · $what, $date';
  }

  @override
  String myTicketStarted(String time) {
    return 'a commencé à $time';
  }

  @override
  String myTicketWhen(int days, String time) {
    String _temp0 = intl.Intl.pluralLogic(
      days,
      locale: localeName,
      other: 'dans $days jours',
      one: 'demain à $time',
      zero: 'aujourd\'hui à $time',
    );
    return '$_temp0';
  }

  @override
  String get cancel => 'Annuler';

  @override
  String get save => 'Enregistrer';

  @override
  String get profileName => 'Nom';

  @override
  String get profileNameEmpty => 'Ajoute ton prénom et ton nom';

  @override
  String get profileEditName => 'Modifier le nom';

  @override
  String get profilePhone => 'Téléphone';

  @override
  String get profileLanguage => 'Langue';

  @override
  String get languageFr => 'Français';

  @override
  String get languageAr => 'العربية';

  @override
  String get languageEn => 'English';

  @override
  String get profileLogout => 'Se déconnecter';

  @override
  String get profileLogoutAll => 'Se déconnecter de tous mes appareils';

  @override
  String get profileLogoutBody => 'Tes billets seront retirés de ce téléphone. Reconnecte-toi pour les retrouver.';

  @override
  String get profileLogoutAllBody =>
      'Ton compte sera déconnecté sur ce téléphone et sur tous tes autres appareils. Tes billets seront retirés de ce téléphone.';

  @override
  String get profileLogoutConfirm => 'Me déconnecter';

  @override
  String get profileLogoutAllFailed =>
      'Ce téléphone est déconnecté, mais tes autres appareils n\'ont pas pu l\'être (pas de réseau). Reconnecte-toi et réessaie.';
}
