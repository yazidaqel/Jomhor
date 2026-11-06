// FR is the reference dictionary: every key exists here. Fans are addressed with "tu".
export const fr = {
  app: {
    name: 'Jomhor',
  },
  login: {
    metaTitle: 'Connexion',
    title: 'Connexion',
    intro: "Saisis ton numéro : on t'envoie un code à 6 chiffres pour accéder à tes billets.",
    phoneLabel: 'Numéro de téléphone',
    phonePlaceholder: '06 12 34 56 78',
    phoneInvalid: 'Saisis un numéro marocain valide, par exemple 06 12 34 56 78.',
    sendCode: 'Recevoir un code',
    codeLabel: 'Code de vérification',
    codeSent: "On t'envoie un code à 6 chiffres au {phone}. Il est valable 5 minutes.",
    codeResent: 'Nouveau code demandé pour le {phone}. Saisis le dernier code reçu.',
    codeInvalid: 'Le code contient 6 chiffres.',
    verify: 'Se connecter',
    changePhone: 'Changer de numéro',
    resend: 'Renvoyer le code',
    resendIn: 'Renvoyer le code ({seconds} s)',
  },
  errors: {
    OTP_INVALID: 'Code incorrect. Vérifie-le ou demande un nouveau code.',
    OTP_EXPIRED: 'Ce code a expiré. Demande un nouveau code.',
    OTP_RATE_LIMITED: 'Trop de demandes pour ce numéro. Réessaie plus tard.',
    VALIDATION_FAILED: 'Certaines informations sont invalides. Vérifie ta saisie.',
    NETWORK: 'Connexion au serveur impossible. Vérifie ton réseau et réessaie.',
    generic: 'Une erreur est survenue. Réessaie.',
  },
}
