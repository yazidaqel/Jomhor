# Tester Jomhor en local

Tout tourne sur ce PC : base de données, backend, boutique web, back-office. Les applications Android (supporter et
scanner) se connectent au PC par le Wi-Fi. Paiements, SMS, WhatsApp et push sont **simulés** : rien ne part en vrai.

## Prérequis

- Docker Desktop démarré.
- Java 25, Node 24 (déjà installés sur ce PC).
- Pour les applis : un téléphone Android (ou un émulateur) **sur le même Wi-Fi que le PC**.

## Démarrer / arrêter

```powershell
powershell -ExecutionPolicy Bypass -File scripts\dev.ps1          # démarrer (2 à 4 min la première fois)
powershell -ExecutionPolicy Bypass -File scripts\dev.ps1 stop     # arrêter, les données restent
powershell -ExecutionPolicy Bypass -File scripts\dev.ps1 reset    # arrêter et repartir de zéro
```

À la fin du démarrage, le script affiche les adresses. Par défaut :

| Quoi | Adresse |
| --- | --- |
| Boutique web | http://localhost:3000 |
| Back-office | http://localhost:5173 |
| API (pour les téléphones) | http://&lt;IP du PC&gt;:8090/v1 |

Si un port est déjà pris, le script prend le suivant (3001, 5174…) : **lisez les adresses affichées**. Le port de
l'API doit rester 8090, car les applis Android sont construites pour lui.

## Démo partenaire (15 min)

Trois écrans : la boutique et le back-office sur le PC, et deux téléphones.

| Appareil | Appli | Compte |
| --- | --- | --- |
| PC | Boutique (onglet 1), back-office (onglet 2) | Yassine 06 00 00 00 10 · admin 06 00 00 00 01 |
| Téléphone A | Appli supporter | Yassine, 06 00 00 00 10 |
| Téléphone B | Appli scanner **et** appli supporter | Agent Porte (PIN 1234) · Salma, 06 00 00 00 11 |

### Avant la démo (20 min avant)

L'horaire du match Sporting Anfa se cale sur le démarrage : coup d'envoi **75 min après**. Démarrez donc la pile
peu avant la démo, pas la veille.

1. `scripts\dev.ps1 reset`, puis `scripts\dev.ps1`. Vérifiez avec `ipconfig` que le PC a bien l'IP
   `192.168.8.148` (sinon les applis ne le joignent pas).
2. Si vous avez fait une répétition : sur les deux téléphones, **Paramètres Android → Applis → Jomhor / Jomhor
   Porte → Stockage → Effacer les données**. Sinon le billet de Yassine reste « Tu es dedans » et la carte membre ne
   rejoue pas son animation.
3. Scanner (téléphone B) : enrôlez-le, approuvez-le et affectez-le à la porte **P1** de Sporting Anfa (étapes 1 à 5
   de « Appli scanner, jour de match » plus bas). Laissez l'appli scanner ouverte.
4. Téléphone B, appli supporter : connectez-vous avec 06 00 00 00 11 (Salma).
5. Push simulé : les APK n'ont pas Firebase, donc aucun téléphone ne reçoit de jeton push. Donnez-en un faux à
   Yassine, sinon la campagne de l'étape 11 a une audience de 0. Dans PowerShell :

   ```powershell
   $api = 'http://localhost:8090/v1'
   Invoke-RestMethod -Method Post "$api/fan/auth/otp" -ContentType 'application/json' -Body '{"phone":"+212600000010"}' | Out-Null
   $fan = Invoke-RestMethod -Method Post "$api/fan/auth/verify" -ContentType 'application/json' -Body '{"phone":"+212600000010","code":"123456"}'
   Invoke-RestMethod -Method Put "$api/fan/me/push-token" -Headers @{ Authorization = "Bearer $($fan.token)" } -ContentType 'application/json' -Body '{"token":"demo-yassine","platform":"android"}'
   ```

   À refaire après chaque `reset`, et après **Téléphone perdu ?** ou **Se déconnecter de tous mes appareils**.
6. Back-office : connectez-vous en 06 00 00 00 01. Le sélecteur en haut doit afficher **ATLAS FC**.
7. Téléphone A : n'ouvrez pas encore l'appli supporter.

### Déroulé

| # | Où | Quoi faire | À montrer |
| --- | --- | --- | --- |
| 1 | Téléphone A | Ouvrez l'appli : l'écran **Tes clubs** s'affiche. **Suivre** ATLAS FC et Olympique Tensift → **Continuer**. Connectez-vous (06 00 00 00 10, 123456). | Une seule appli Jomhor pour tous les clubs. Sur l'**Accueil**, les onglets **Mes clubs**, un par club et **Tout**. Le club n'apparaît que par son écusson et son nom. Dès la connexion, l'appli s'ouvre sur le billet Sporting Anfa (voir étape 8) : fermez-le pour revenir à l'accueil. |
| 2 | PC, boutique | **Se connecter** (06 00 00 00 10) → **Olympique Tensift vs Union Rif** → une zone, 1 place → **Continuer** → nom → **Carte bancaire** → **Payer …** → page **Paiement simulé** → **Approuver**. | Page d'événement partageable, achat en trois étapes, « Paiement confirmé ». *Simulé : la page de paiement remplace Payzone.* |
| 3 | Téléphone A | **Billets** (tirez vers le bas si besoin) : le billet Olympique Tensift vient d'arriver. Ouvrez le billet Sporting Anfa, puis passez en mode avion. | Le QR change toutes les 30 s (« Nouveau code dans … s »), « Fonctionne sans réseau ». Aucune donnée personnelle dans le QR. Repassez le Wi-Fi ensuite. |
| 4 | Téléphone A | **ATLAS FC vs Rapid Oued** → **Prendre mes places** → zone Est, 3 billets → **Continuer**. Billet 2 : `Salma El Idrissi`, 06 00 00 00 11, **Paie sa place**. Billet 3 : un nom, 06 00 00 00 12, **Paie sa place**. Cochez la case, **Carte bancaire** → **Payer …** → **Approuver**. | Le récapitulatif « Tu paies 1 place · … », « 2 places payées par tes amis ». Après paiement : **Ta bande**, **1/3 payées**, une puce « À payer · avant … » par ami, **Relancer sur WhatsApp**. Laissez cet écran ouvert. |
| 5 | Téléphone B, appli supporter | **Accueil** → tirez vers le bas → bandeau « Ta place t'attend · avant … » → **Carte bancaire** → **Approuver**. | « Yassine B. t'a gardé une place ». Sur le téléphone A, la puce de Salma passe à **Payé** et le compteur à **2/3 payées** (en 10 s environ). *Simulé : l'invitation WhatsApp n'est qu'une ligne `template split_invite` dans `scripts\.dev\backend.log`.* |
| 6 | Téléphone A | **Billets** → billet Honneur de Rapid Oued → **Transférer** → 06 00 00 00 11, un nom → **Continuer** → **Maintiens pour transférer**. | Le billet glisse hors du portefeuille, la ligne « Transféré à … » apparaît. Sur le téléphone B, **Billets** : Salma a un nouveau billet, l'ancien QR ne passe plus. Sur Sporting Anfa, **Transférer** est grisé (« Transferts fermés … avant le début »). |
| 7 | Téléphone A | Onglet **Club** → **Rejoindre le club · gratuit** → activez **Notifications du club** → **Rejoindre**. | La carte membre monte et se pose : MEMBRE N° 000001, « depuis … », niveau **Fidèle**. **Ta saison** en douze points : 7 blancs (présent), 4 vides (manqués), le 12e en ambre pour le prochain match. « 7 matchs au stade », « Encore 3 pour Pilier », la liste **Derniers** (**J'y étais** / **Manqué**). Glissez : la carte Olympique Tensift. |
| 8 | Téléphone A | Ouvrez le billet Sporting Anfa (l'appli l'ouvre d'elle-même au retour). Touchez **Itinéraire** sous le billet. | **MODE JOUR DE MATCH** et le halo. Le billet se retourne : porte P1, texte d'accès, **Ouvrir dans Plans**, **Autres portes de ta zone** (P2), la puce d'affluence **Fluide** sur P1. **Retourner le billet**. |
| 9 | Téléphone B, appli scanner | Scannez le QR du téléphone A. Gardez le téléphone A sur le billet, Wi-Fi allumé. | Scanner : **VALIDE** en vert, initiales du titulaire, validé hors ligne en quelques ms. Un 2e scan : **DÉJÀ UTILISÉ**. Téléphone A : en une minute environ, le billet se retourne sur **TU ES DEDANS**, « Entré à … · Porte P1 ». Onglet **Club**, tirez vers le bas : « 8 matchs au stade ». |
| 10 | PC, back-office | **Portes en direct** → Sporting Anfa. Puis **Aperçu**, puis **Événements** → Rapid Oued → **Ventes**. | Les entrées en direct (**Entrés**, **Cadence**, **Par porte** avec la charge). Le remplissage par zone et les ventes par canal (Application, Site web, Guichet). |
| 11 | PC, back-office → téléphone A | **Fans · CRM** → onglet **Membres** (1 membre, opt-in push). Onglet **Campagnes** → **Nouvelle campagne** → Canal **Push**, Audience **Membres opt-in**, Événement Rapid Oued, un titre et un message → **Enregistrer le brouillon** → **Envoyer maintenant** → **Envoyer**. Sur le téléphone A, une minute après : **Accueil**, tirez vers le bas, touchez la cloche. | « Audience estimée : 1 fan ». Le message arrive dans **Notifications** avec l'écusson du club, et ouvre le match. *Simulé : pas de notification sur le téléphone, juste la boîte de réception et une ligne `[dev push]` dans le log.* |
| 12 | Téléphone A, puis boutique | **Profil** → **Langue** → **العربية**. Boutique : le globe en haut → العربية. | Tout passe de droite à gauche, en darija. Les prix, les numéros et les codes restent lisibles de gauche à droite. *Le texte en darija est un premier jet.* |

Ne clôturez pas Sporting Anfa pendant la démo : un match clôturé ne se recale plus, il faudrait un `reset`.

## Comptes de test

Code de connexion pour **tous** les numéros : **123456** (aucun SMS n'est envoyé). Saisissez les numéros au format
`06 00 00 00 01`.

| Rôle | Numéro | Où |
| --- | --- | --- |
| Admin du club (ATLAS FC et Olympique Tensift) | 06 00 00 00 01 | Back-office |
| Caissier | 06 00 00 00 02 | Back-office → Guichet |
| Opérations | 06 00 00 00 03 | Back-office |
| Finance | 06 00 00 00 04 | Back-office |
| Supporter avec billets (Yassine Benali) | 06 00 00 00 10 | Boutique, appli supporter |
| Supporter sans billet (Salma El Idrissi) | 06 00 00 00 11 | Boutique, appli supporter |
| Agent de porte | PIN 1234 | Appli scanner |
| Chef de porte | PIN 5678 | Appli scanner |

Tout autre numéro en 06 marche aussi avec 123456 : le compte est créé à la première connexion.

## Données de démo

Les horaires se recalent à chaque démarrage :

- **ATLAS FC vs Rapid Oued** : en vente, coup d'envoi dans 10 jours. Paiement par carte, wallet ou code cash.
  Paiement partagé possible.
- **ATLAS FC vs Sporting Anfa** : coup d'envoi 75 min après le démarrage, portes ouvertes. Sert au scanner et au
  jour de match. La vente ferme 1 h avant (15 min après le démarrage), les transferts 1 h 30 avant (déjà fermés), et
  le paiement partagé y est impossible.
- **ATLAS FC vs Union Rif** : match passé, il y a 7 jours.
- **10 matchs d'archive d'ATLAS FC** (Étoile Souss, Chabab Draa, … Nahda Dakhla), une semaine d'écart avant Union
  Rif, tous clôturés.
- **Olympique Tensift vs Union Rif** : deuxième club de démo (Stade Tensift, Marrakech), en vente, coup d'envoi
  dans 6 jours.

Yassine (06 00 00 00 10) a un billet Honneur pour chacun des 13 matchs d'ATLAS FC, et un billet pour le match
d'Olympique Tensift. Il est entré à 7 des 10 matchs d'archive (pas à Ittihad Saïss, Hilal Oriental ni Fath
Bouregreg), ni à Union Rif.

Personne n'est membre d'un club au départ. Chaque porte a un texte d'accès et une position sur la carte, fictifs,
modifiables dans le back-office (**Paramètres** → **Lieux**).

## Scénarios

Chaque scénario dit quoi faire et ce que vous devez voir.

### 1. Achat par carte (boutique)

1. Boutique → match Rapid Oued → choisissez une zone et 1 place → nom du titulaire → **Carte bancaire**.
2. Connexion avec 06 00 00 00 11, code 123456.
3. La page de paiement simulée s'ouvre : **Approuver**.

Attendu : la commande passe à **Payée**, et le billet apparaît dans **Mes billets** avec son PDF.

### 2. Code cash payé au guichet

1. Boutique, même match → moyen **Cash en agence** → **Obtenir mon code cash**. Notez le code `JMH XXXX XX`.
2. Back-office → connexion 06 00 00 00 02 (caissier) → **Guichet** → ouvrez la caisse → onglet **Code cash** →
   saisissez le code → **Encaisser** le montant exact.

Attendu : côté boutique, la commande passe à **Payée** et le billet apparaît. Au guichet, le ticket 80 mm s'imprime.

### 3. Vente au guichet (sans compte)

Guichet → **Vente** → zone, quantité, noms (un nom en arabe, par exemple `يزيد عقل`) → encaissez → imprimez.

Attendu : billets imprimés avec QR. Le nom arabe s'affiche correctement.

### 4. Transfert de billet

1. Boutique → **Mes billets** connecté en 06 00 00 00 10 → billet Rapid Oued → **Transférer**.
2. Numéro 06 00 00 00 11, nom `Karim Benali` → **Continuer** → **Transférer le billet**.

Attendu : « Billet transféré ». Le billet devient « Transféré à Karim B. ». Connecté en 06 00 00 00 11, Karim voit un
nouveau billet (nouveau numéro). Dans le back-office, **Événements** → Rapid Oued → **Billets** → recherche → les
colonnes « Transféré vers » et « Transféré depuis » s'affichent.

Pour le match Sporting Anfa, le bouton **Transférer** est grisé : « Transferts fermés 1 h 30 min avant le début ».

### 5. Téléphone perdu (appli supporter)

Appli supporter → **Profil** → **Téléphone perdu ?** → **Nouveaux QR**.

Attendu : nouveaux billets avec de nouveaux numéros. Les anciens QR ne passent plus au scanner, et les autres
téléphones connectés sur ce compte sont déconnectés.

### 6. Arabe et anglais

- Boutique : ajoutez `?lang=ar` à l'adresse, ou utilisez le globe en haut. Tout passe de droite à gauche, en
  darija. Les prix et les codes restent lisibles de gauche à droite.
- Appli supporter : **Profil** → **Langue** → العربية.

### 7. Back-office : ventes, encaissements, CSV

Connexion 06 00 00 00 01 (admin) ou 06 00 00 00 04 (finance) :

- **Événements** → Rapid Oued → bouton **Ventes** : ventes par zone, canal et moyen de paiement, remboursements.
- **Finance** → **Encaissements** : jours, caisses, écarts. **Exporter CSV** : le fichier s'ouvre dans Excel
  (séparateur `;`).

### 8. Bloquer, rembourser

Back-office → **Événements** → Rapid Oued → **Billets** → recherchez `06 00 00 00 10` :

- **Bloquer** un billet : il passe en BLOQUÉ et le scanner le refuse.
- **Rembourser** un billet : il apparaît dans **Ventes** → **Billets remboursés**. Finance clique **Marquer
  remboursé**.

### 9. Clubs suivis

Boutique, `http://localhost:3000/` :

1. Sans club suivi, l'onglet **Tout** est choisi : les matchs des deux clubs, chacun avec l'écusson et le nom du club.
2. **Suivre** sur une carte ATLAS FC : la carte affiche **Suivi**, vous restez sur **Tout**, et les onglets
   **Mes clubs** et **ATLAS FC** apparaissent. **Mes clubs** ne montre qu'ATLAS FC. Ça marche aussi avec JavaScript
   désactivé.
3. La bande **Clubs** en bas mène à `/atlas-fc` et `/olympique-tensift`, la page de chaque club.
4. Un ancien lien `/events/…` redirige vers `/atlas-fc/events/…`.
5. **Mes billets** (06 00 00 00 10) montre les billets des deux clubs, chacun avec son club.

L'appli et la boutique restent Jomhor (logo, couleurs) : le club n'apparaît que par son écusson et son nom.

### 10. Jour de match (appli supporter + scanner)

Préparez le scanner (« Appli scanner, jour de match », étapes 1 à 5) et laissez-le ouvert. Appli supporter connectée
en 06 00 00 00 10.

1. Ouvrez l'appli : dès 4 h avant le coup d'envoi, elle s'ouvre directement sur le billet Sporting Anfa, avec
   **MODE JOUR DE MATCH**.
2. Sous le billet : porte P1, puce **Fluide**, bouton **Itinéraire**. Touchez **Itinéraire** (ou la case porte du
   billet) : le billet se retourne. Porte, texte d'accès, **Ouvrir dans Plans**, **Autres portes de ta zone** (P2).
   **Retourner le billet** revient au QR.
3. En mode avion, la face porte et le texte d'accès s'affichent toujours. Seule la puce disparaît, au bout de 2 min.
4. Scannez le QR avec le scanner, en gardant le billet affiché et le Wi-Fi allumé.

Attendu : en une minute environ, le billet se retourne sur **TU ES DEDANS**, « Entré à … · Porte P1 », avec une
vibration. Le billet ne peut plus être transféré.

Les puces d'affluence ne s'affichent que de 3 h avant à 1 h après le coup d'envoi, et seulement pour les portes qui
ont un scanner affecté et actif dans les 2 dernières minutes. Avec un seul scanner, P1 reste **Fluide** : **Modérée**
et **Chargée** demandent du vrai trafic.

### 11. Adhésion et fidélité (onglet Club)

Appli supporter connectée en 06 00 00 00 10, avec au moins un club suivi.

1. Onglet **Club** : une carte par club suivi. Sans adhésion, une carte en contour et **Rejoindre le club ·
   gratuit**.
2. Touchez-le : la feuille **Rejoindre ATLAS FC** propose **Notifications du club** et **Messages WhatsApp du club**,
   désactivés par défaut. **Rejoindre**.
3. La carte membre apparaît : « MEMBRE N° 000001 » (numéro attribué dans l'ordre des adhésions), « depuis … », niveau
   **Fidèle**.
4. **Ta saison** : 12 points. Les 11 derniers matchs clôturés (7 pleins pour Yassine, 4 vides) et le 12e en ambre
   pour le prochain. « 7 matchs au stade », « Encore 3 pour Pilier ». **Derniers** : **J'y étais** ou **Manqué**.
5. **Messages du club** : les deux interrupteurs se changent à tout moment. **Quitter le club** → **Quitter**. En
   revenant, le numéro est conservé.
6. L'**Accueil** affiche une bande membre (N°, nombre de matchs).

Back-office → **Fans · CRM** → **Membres** : le nombre de membres, de nouveaux sur 7 jours, d'opt-in push et WhatsApp,
et les niveaux de fidélité. Des totaux uniquement, sans nom ni numéro.

Sans club suivi, l'onglet **Club** affiche **Choisis tes clubs**. Un point de **Ta saison** ne s'allume qu'une fois
le match clôturé.

### 12. Paiement partagé (appli et boutique)

Sur **Rapid Oued** uniquement : le partage se fait au moins 2 h avant la fin de la vente. Limite : 4 billets par
compte, places des amis non payées comprises.

**Dans l'appli** (06 00 00 00 10) :

1. Rapid Oued → **Prendre mes places** → une zone, 2 ou 3 billets → **Continuer**.
2. Pour un billet d'ami : un nom, **WhatsApp de ton ami (facultatif)** = 06 00 00 00 11, puis **Paie sa place**.
   Cochez la case de consentement.
3. Le récapitulatif affiche « Tu paies 1 place · … » et « … payées par tes amis », « Tes amis ont 2 h après ton
   paiement ». **Carte bancaire** → **Payer …** → **Approuver**.
4. Écran **Ta bande** : « 1/2 payées » (ou 1/3), une puce « À payer · avant … » par ami, **Relancer sur WhatsApp** et
   **Copier le lien**.

**L'ami** (06 00 00 00 11) dans l'appli : **Accueil** → bandeau « Ta place t'attend · avant … » → « Yassine B. t'a
gardé une place » → payez par carte, wallet ou cash. Sur l'écran **Ta bande** de Yassine, sa puce passe à **Payé**.

**Sur la boutique** : même parcours. Le choix **Paie sa place** apparaît sous le numéro WhatsApp d'un billet. Après
paiement, la page de commande montre **Ta bande · ATLAS FC vs Rapid Oued** et **Copier le lien**. Ouvrez ce lien
dans une fenêtre de navigation privée, connecté en 06 00 00 00 11 : « Yassine B. t'a gardé une place », **Ta
place**, et le délai. Une commande payée dans l'appli peut se payer sur la boutique, et inversement.

Attendu aussi :

- un numéro d'ami égal au vôtre est refusé (« Ce numéro est le tien ») ;
- tous les billets en **Paie sa place** sont refusés ;
- sur Sporting Anfa, tant que sa vente est ouverte, l'interrupteur affiche « Trop tard pour partager » ;
- l'ami qui ne paie pas dans les 2 h : sa place est libérée (« La place a été libérée »), sa puce passe à **Expiré**.

L'invitation WhatsApp et le push à l'ami sont simulés : une ligne `template split_invite` dans
`scripts\.dev\backend.log`.

### 13. Campagnes push et WhatsApp, boîte de réception

Back-office en 06 00 00 00 01 (admin) ou 06 00 00 00 03 (opérations). Finance voit les campagnes sans pouvoir en
créer.

**Push vers les membres :**

1. Prérequis : Yassine est membre d'ATLAS FC avec **Notifications du club** activé (scénario 11), et a un jeton push
   factice (« Avant la démo », étape 5). Sans jeton, l'audience est de 0.
2. **Fans · CRM** → **Campagnes** → **Nouvelle campagne** → Canal **Push**, Audience **Membres opt-in**,
   **Événement (facultatif)**, **Titre** (60 caractères au plus), **Message** (500 au plus), en français ou en
   arabe.
3. **Enregistrer le brouillon** : « Audience estimée : 1 fan ». **Envoyer maintenant** → **Envoyer**.
4. La campagne passe de **En cours** à **Envoyée** (colonnes **Destinataires**, **Envoyés**, **Échecs**).
5. Appli supporter (06 00 00 00 10) → **Accueil** → tirez vers le bas : un point sur la cloche. Touchez-la :
   **Notifications** liste le message avec l'écusson et le nom du club. Touchez-le : il ouvre l'événement.

**WhatsApp vers les détenteurs :** Canal **WhatsApp**, Audience **Détenteurs d'un billet**, Événement Sporting Anfa,
par exemple « Ouverture des portes ». Une ligne `template event_info` par détenteur dans `scripts\.dev\backend.log`.
Les messages WhatsApp n'apparaissent pas dans la cloche.

**Programmer :** au lieu d'**Envoyer maintenant**, choisissez une date et une heure → **Programmer**. Une campagne
programmée peut être annulée (**Annuler**) jusqu'à son envoi.

### 14. Back-office : accès aux portes

**Paramètres** → **Lieux** → Grand Stade → **Portes** → **Modifier la porte P1** :

- **Accès** : le texte affiché dans l'appli supporter (200 caractères au plus) ;
- **Latitude** et **Longitude** : les deux ou aucune. **Voir sur la carte** ouvre la position.

Attendu : le nouveau texte apparaît sur la face porte du billet après le rafraîchissement des billets (au retour dans
l'appli). Sans position, **Ouvrir dans Plans** disparaît.

## Applications mobiles

Les deux fichiers APK sont construits pour ce PC (`http://192.168.8.148:8090/v1`). Si l'IP du PC change, demandez une
reconstruction.

| Appli | Fichier |
| --- | --- |
| Supporter | `dist\jomhor-fan-debug.apk` |
| Scanner | `dist\jomhor-scanner-debug.apk` |

**Installer** : copiez l'APK sur le téléphone, ouvrez-le et autorisez l'installation depuis cette source. Ou, avec
`adb` : `adb install dist\jomhor-fan-debug.apk`.

Si le téléphone n'arrive pas à joindre le PC, autorisez le port 8090 dans le pare-feu Windows (réseau privé).

**Réseau domestique uniquement** : pendant le test, n'importe qui sur le même Wi-Fi peut se connecter avec le code
123456. Les données sont fictives, mais ne lancez pas la pile sur un Wi-Fi public ou partagé.

### Appli supporter

1. Premier lancement : l'écran **Tes clubs** s'ouvre. Suivez un club puis **Continuer**, ou **Tout voir**. Sur
   l'accueil, les onglets **Mes clubs**, un par club suivi, et **Tout** changent la liste. **Profil** → **Mes clubs**
   rouvre l'écran.
2. Connexion 06 00 00 00 10, code 123456.
3. **Billets** : le billet Sporting Anfa affiche un QR qui tourne, et il s'affiche aussi en mode avion. Tant que
   Sporting Anfa est en mode jour de match, l'appli s'ouvre sur ce billet à chaque retour sur l'accueil ou les
   billets : fermez-le pour revenir.
4. Essayez le transfert : maintenez le bouton jusqu'au bout. Le billet glisse hors du portefeuille.
5. Quatre onglets : **Accueil** (avec la cloche des notifications), **Billets**, **Club**, **Profil**.

### Appli scanner, jour de match (Sporting Anfa)

1. Back-office (admin) → **Appareils** → **Ajouter des appareils** : un QR d'enrôlement s'affiche (valable 30 min).
2. Appli scanner → **Scanner le QR d'enrôlement** → **Nom de l'appareil** (par exemple `PDA Porte 1`) → **Enrôler
   l'appareil**. L'écran affiche « En attente d'approbation » et une empreinte.
3. Back-office → **Approuver** → vérifiez l'empreinte → **L'empreinte correspond · approuver**.
4. **Événements** → ATLAS FC vs Sporting Anfa → onglet **Appareils** → porte **P1 · Porte 1** → **Enregistrer**.
   Si le scanner affiche encore « Aucun événement assigné à cet appareil. », fermez l'appli scanner et rouvrez-la.
5. Scanner : touchez le match → **Charger le manifeste**. Déverrouillez : **Agent Porte**, PIN **1234**. L'heure du
   téléphone doit être automatique (écart de 20 s au plus).
6. Scannez le QR du billet de Yassine (sur l'appli supporter ou le PDF).

Attendu : **VALIDE** (vert), avec les initiales du titulaire. Un deuxième scan du même billet donne **DÉJÀ
UTILISÉ**. Un billet bloqué ou transféré (ancien QR) donne **ANNULÉ**. Le scan marche aussi en mode avion.

Back-office → **Portes en direct** → Sporting Anfa : les entrées montent en direct.

## Limites connues (test local)

- Paiements simulés : rien n'est débité. La page **Paiement simulé** remplace Payzone. Pour un « refus », choisissez
  **Refuser**.
- Pas de vrai SMS : le code 123456 marche pour tous les numéros.
- WhatsApp simulé : les envois sont écrits dans `scripts\.dev\backend.log`.
- Push simulé : il n'y a pas encore de projet Firebase. Les push apparaissent dans `scripts\.dev\backend.log` et dans
  la boîte de réception (la cloche), jamais en notification sur le téléphone. Les APK n'enregistrent aucun jeton
  push : sans le jeton factice (« Avant la démo », étape 5), une campagne push a une audience de 0.
- Affluence : avec un seul scanner, la puce reste **Fluide**.
- Sporting Anfa ne se recale qu'au redémarrage, et plus du tout une fois clôturé : `reset` dans ce cas. Un billet
  scanné reste « Tu es dedans » jusqu'au `reset`.
- La salle d'attente des grosses ventes (Lot 5) n'est pas incluse.
- Le texte en darija est un premier jet, à relire par un natif.
