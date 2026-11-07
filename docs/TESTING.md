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

## Comptes de test

Code de connexion pour **tous** les numéros : **123456** (aucun SMS n'est envoyé). Saisissez les numéros au format
`06 00 00 00 01`.

| Rôle | Numéro | Où |
| --- | --- | --- |
| Admin du club | 06 00 00 00 01 | Back-office |
| Caissier | 06 00 00 00 02 | Back-office → Guichet |
| Opérations | 06 00 00 00 03 | Back-office |
| Finance | 06 00 00 00 04 | Back-office |
| Supporter avec billets (Yassine) | 06 00 00 00 10 | Boutique, appli supporter |
| Supporter sans billet | 06 00 00 00 11 | Boutique, appli supporter |
| Agent de porte | PIN 1234 | Appli scanner |
| Chef de porte | PIN 5678 | Appli scanner |

## Données de démo

Les horaires se recalent à chaque démarrage :

- **ATLAS FC vs Rapid Oued** : en vente, coup d'envoi dans 10 jours. Paiement par carte, wallet ou code cash.
- **ATLAS FC vs Sporting Anfa** : coup d'envoi dans 75 min, portes ouvertes. Sert au scanner et au jour de match.
  Les transferts y sont déjà fermés (ils ferment 1 h 30 avant).
- **ATLAS FC vs Union Rif** : match passé.

Yassine (06 00 00 00 10) a un billet Honneur pour chacun des trois matchs.

## Scénarios

Chaque scénario dit quoi faire et ce que vous devez voir.

### 1. Achat par carte (boutique)

1. Boutique → match Rapid Oued → choisissez une zone et 1 place → nom du titulaire → **Carte**.
2. Connexion avec 06 00 00 00 11, code 123456.
3. La page de paiement simulée s'ouvre : **Approuver**.

Attendu : la commande passe à **Payée**, et le billet apparaît dans **Mes billets** avec son PDF.

### 2. Code cash payé au guichet

1. Boutique, même match → moyen **Espèces (code)**. Notez le code `JMH XXXX XX`.
2. Back-office → connexion 06 00 00 00 02 (caissier) → **Guichet** → ouvrez la caisse → saisissez le code →
   **Encaisser** le montant exact.

Attendu : côté boutique, la commande passe à **Payée** et le billet apparaît. Au guichet, le ticket 80 mm s'imprime.

### 3. Vente au guichet (sans compte)

Guichet → **Vente** → zone, quantité, noms (un nom en arabe, par exemple `يزيد عقل`) → encaissez → imprimez.

Attendu : billets imprimés avec QR. Le nom arabe s'affiche correctement.

### 4. Transfert de billet

1. Boutique → **Mes billets** connecté en 06 00 00 00 10 → billet Rapid Oued → **Transférer**.
2. Numéro 06 00 00 00 11, nom `Karim Benali` → **Continuer** → **Transférer le billet**.

Attendu : « Billet transféré ». Le billet devient « Transféré à Karim B. ». Connecté en 06 00 00 00 11, Karim voit un
nouveau billet (nouveau numéro). Dans le back-office, **Billets** → recherche → la chaîne « Transféré vers / depuis »
s'affiche.

Pour le match Sporting Anfa, le bouton **Transférer** est grisé : « Transferts fermés 1 h 30 min avant le début ».

### 5. Téléphone perdu (appli supporter)

Appli supporter → **Profil** → **Téléphone perdu ?** → confirmer.

Attendu : nouveaux billets avec de nouveaux numéros. Les anciens QR ne passent plus au scanner, et les autres
téléphones connectés sur ce compte sont déconnectés.

### 6. Arabe et anglais

- Boutique : ajoutez `?lang=ar` à l'adresse, ou utilisez le globe en haut. Tout passe de droite à gauche, en
  darija. Les prix et les codes restent lisibles de gauche à droite.
- Appli supporter : **Profil** → **Langue** → العربية.

### 7. Back-office : ventes, encaissements, CSV

Connexion 06 00 00 00 01 (admin) ou 06 00 00 00 04 (finance) :

- **Événements** → Rapid Oued → onglet **Ventes** : ventes par zone, canal et moyen de paiement, remboursements.
- **Encaissements** : jours, caisses, écarts. **Exporter CSV** : le fichier s'ouvre dans Excel (séparateur `;`).

### 8. Bloquer, rembourser

Back-office → **Billets** → recherchez `06 00 00 00 10` :

- **Bloquer** un billet : il passe en BLOQUÉ et le scanner le refuse.
- **Rembourser** un billet : il apparaît dans la liste des remboursements à faire. Finance le marque **Fait**.

## Applications mobiles

Les deux fichiers APK sont construits pour ce PC (`http://10.95.9.122:8090/v1`). Si l'IP du PC change, demandez une
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

1. Connexion 06 00 00 00 10, code 123456.
2. **Billets** : le billet Sporting Anfa affiche un QR qui tourne, et il s'affiche aussi en mode avion.
3. Essayez le transfert : maintenez le bouton jusqu'au bout. Le billet glisse hors du portefeuille.

### Appli scanner, jour de match (Sporting Anfa)

1. Back-office (admin) → **Événements** → Sporting Anfa → onglet **Appareils** : un QR d'enrôlement s'affiche.
2. Appli scanner → scannez ce QR → l'appareil apparaît « En attente » → **Approuver** dans le back-office, puis
   affectez-le au match et à la porte **P1**.
3. Scanner : déverrouillez avec le PIN **1234**. Le manifeste se télécharge.
4. Scannez le QR du billet de Yassine (sur l'appli supporter ou le PDF).

Attendu : **VALIDE** (vert), avec les initiales du titulaire. Un deuxième scan du même billet donne **DÉJÀ
UTILISÉ**. Un billet bloqué ou transféré (ancien QR) donne **ANNULÉ**. Le scan marche aussi en mode avion.

Back-office → **Portes en direct** → Sporting Anfa : les entrées montent en direct.

## Limites connues (test local)

- Paiements simulés : rien n'est débité. Pour un « refus », choisissez **Refuser** sur la page simulée.
- Pas de vrai SMS, WhatsApp ou push : les envois sont écrits dans `scripts\.dev\backend.log`.
- Le texte en darija est un premier jet, à relire par un natif.
- Pas encore livrés dans le Lot 4 : jour de match enrichi (porte, affluence, « Tu es dedans »), adhésion et fidélité,
  paiement partagé, campagnes push et WhatsApp.
