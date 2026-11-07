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
  String errorPhoneInvalid(String example) {
    return 'Ce numéro ne semble pas marocain. Essaie par exemple $example.';
  }

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
  String get holderNameError =>
      'Prénom et nom en lettres latines ou arabes, sans mélanger les deux, de 2 à 60 caractères.';

  @override
  String get holderPhoneLabel => 'WhatsApp de ton ami (facultatif)';

  @override
  String get holderPhoneHelp =>
      'Il reçoit son billet sur WhatsApp et dans l\'app. Sans numéro, le billet reste chez toi.';

  @override
  String holderPhoneError(String example) {
    return 'Numéro marocain invalide. Essaie $example.';
  }

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
  String get methodWallet => 'Wallet mobile';

  @override
  String get methodWalletHint => 'Paiement depuis ton wallet';

  @override
  String get methodCash => 'Cash en agence';

  @override
  String get methodCashHint => 'Un code à payer au guichet du club ou en agence';

  @override
  String methodCashBefore(String deadline) {
    return 'Avant $deadline';
  }

  @override
  String get methodCashTooLate => 'Trop tard pour le cash';

  @override
  String payWalletCta(String total) {
    return 'Payer $total par wallet';
  }

  @override
  String get payCashCta => 'Obtenir mon code cash';

  @override
  String get errorCashTooLate =>
      'Trop tard pour payer en cash : la vente ferme bientôt. Choisis un autre moyen de paiement.';

  @override
  String get errorCashCap =>
      'Le paiement cash n\'est plus possible pour cet événement pour le moment. Choisis un autre moyen de paiement.';

  @override
  String get errorMethodNotConfigured => 'Ce moyen de paiement n\'est pas disponible. Choisis-en un autre.';

  @override
  String get cashTitle => 'Ton code de paiement cash';

  @override
  String cashHoldBanner(String deadline) {
    return 'Places réservées · paie avant $deadline';
  }

  @override
  String get cashCodeLabel => 'Code à présenter';

  @override
  String cashExactAmount(String amount) {
    return 'Montant exact à régler : $amount';
  }

  @override
  String get cashCopy => 'Copier le code';

  @override
  String get cashCopied => 'Code copié';

  @override
  String get cashTimeLeft => 'Temps restant';

  @override
  String get cashWhereTitle => 'Où payer';

  @override
  String cashNetworks(String names) {
    return 'En agence : $names';
  }

  @override
  String get cashStepsTitle => 'Comment ça marche';

  @override
  String get cashStep1Title => 'Va au guichet ou en agence';

  @override
  String get cashStep2Title => 'Donne le code et le montant exact';

  @override
  String get cashStep3Title => 'Tes billets arrivent';

  @override
  String get cashStep3Body => 'Dans l\'app et sur WhatsApp, dès que le paiement est enregistré.';

  @override
  String get cashPayNowTitle => 'Tu préfères payer maintenant ?';

  @override
  String get cashPayByCard => 'Payer par carte';

  @override
  String get cashPayByWallet => 'Payer par wallet';

  @override
  String get cashHostedPending =>
      'Un paiement par carte ou wallet est en cours de confirmation. Ton code reste valable en attendant.';

  @override
  String get cashStatus => 'En attente de paiement · cette page se met à jour toute seule.';

  @override
  String get cashExpiredTitle => 'Code expiré · places libérées';

  @override
  String get cashExpiredBody =>
      'Le délai pour payer ce code est passé. Tu peux refaire une commande s\'il reste des places.';

  @override
  String get backToEvent => 'Retour à l\'événement';

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
  String get homeCashTitle => 'Commande en attente de paiement';

  @override
  String homeCashDeadline(String deadline) {
    return 'Code cash à payer avant $deadline';
  }

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

  @override
  String get loading => 'Chargement';

  @override
  String homeGreeting(String name) {
    return 'Salut $name';
  }

  @override
  String get homeGreetingNoName => 'Salut';

  @override
  String get homeGreetingGuest => 'Bienvenue';

  @override
  String get homeEmptyTitle => 'Rien au programme';

  @override
  String get homeOpenProfile => 'Mon profil';

  @override
  String get ticketsEmptyTitle => 'Pas encore de billet';

  @override
  String get ticketOpen => 'Ouvrir le billet';

  @override
  String get poweredBy => 'Propulsé par Jomhor';

  @override
  String get transferAction => 'Transférer';

  @override
  String get transferEyebrow => 'Transférer ton billet';

  @override
  String get transferTitle => 'À qui le donnes-tu ?';

  @override
  String transferUntil(String when) {
    return 'Possible jusqu\'au $when';
  }

  @override
  String get transferPhone => 'Numéro WhatsApp';

  @override
  String get transferPhoneHint => 'Le nouveau billet arrive sur ce numéro, dans l\'app et sur WhatsApp.';

  @override
  String get transferName => 'Nom et prénom';

  @override
  String get transferNameHint => 'Le nouveau billet sera à ce nom.';

  @override
  String get transferNext => 'Continuer';

  @override
  String get transferConfirmTitle => 'Confirmer le transfert';

  @override
  String get transferTo => 'À';

  @override
  String get transferTicket => 'Billet';

  @override
  String transferWarning(String name) {
    return 'Ton QR ne marchera plus. $name reçoit un nouveau billet.';
  }

  @override
  String get transferFinal => 'Un transfert est définitif : tu ne pourras pas l\'annuler.';

  @override
  String get transferEdit => 'Modifier';

  @override
  String get transferHold => 'Maintiens pour transférer';

  @override
  String get transferHoldHint => 'Garde le doigt appuyé jusqu\'à ce que la barre soit pleine.';

  @override
  String get transferPending => 'Transfert en cours…';

  @override
  String transferDone(String name) {
    return '$name reçoit son billet dans l\'app et sur WhatsApp. Ton ancien QR ne marche plus.';
  }

  @override
  String get transferSelf => 'C\'est ton numéro : ce billet est déjà à toi.';

  @override
  String get transferNotValid => 'Ce billet n\'est plus valide : il ne peut pas être transféré.';

  @override
  String get transferAdmitted => 'Ce billet est déjà passé à la porte : il ne peut plus être transféré.';

  @override
  String get transferUnknown =>
      'On n\'a pas pu confirmer le transfert. Tes billets se rechargent : vérifie-les avant de réessayer.';

  @override
  String transferClosed(String lead) {
    return 'Transferts fermés $lead avant le début';
  }

  @override
  String get transferUnavailable => 'Pas de transfert possible pour ce billet';

  @override
  String durationHours(int n) {
    return '$n h';
  }

  @override
  String durationMinutes(int n) {
    return '$n min';
  }

  @override
  String transferRow(String name, String date) {
    return 'Transféré à $name · $date';
  }

  @override
  String transferRowNoName(String date) {
    return 'Transféré · $date';
  }

  @override
  String get profileLostPhone => 'Téléphone perdu ?';

  @override
  String get profileLostPhoneHint => 'Un nouveau QR pour tes billets à venir';

  @override
  String get lostPhoneBody =>
      'Tes billets à venir reçoivent un nouveau QR sur ce téléphone. Ceux du téléphone perdu ne passeront plus.';

  @override
  String get lostPhoneSignedOut =>
      'Le téléphone perdu sera déconnecté : il ne pourra pas recevoir tes nouveaux billets. Ce téléphone-ci reste connecté.';

  @override
  String get lostPhoneNone => 'Aucun billet à venir ne peut recevoir de nouveau QR.';

  @override
  String get lostPhoneConfirm => 'Nouveaux QR';

  @override
  String lostPhoneDone(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Nouveaux QR prêts pour $count billets.',
      one: 'Nouveau QR prêt pour 1 billet.',
    );
    return '$_temp0';
  }

  @override
  String get lostPhoneFailed => 'Certains billets n\'ont pas reçu de nouveau QR. Vérifie tes billets.';
}
