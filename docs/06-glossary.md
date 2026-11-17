# Glossary

| Code / term | FR (UI) | AR | Meaning |
| --- | --- | --- | --- |
| Jomhor | Jomhor | جمهور | Product name. Arabic/Darija: "the crowd", the fans — the 12th man |
| organizer | organisateur | المنظم | Tenant: club, promoter, venue |
| event | événement / match | الحدث / المباراة | One ticketed occurrence; `event_type` says what kind |
| event_series | série | سلسلة | A season, a tour, a festival edition; groups events; passes attach here |
| zone | tribune / zone | المدرج | Sector of a venue; `zone_id` travels in the QR |
| gate | porte | البوابة | Physical entrance; serves one or more zones |
| pass | abonnement | اشتراك الموسم | Season pass: every match of the season in one zone, materialised as one ticket per event |
| pass seats | places abonnés | مقاعد المشتركين | A zone's places for pass holders (price category audience `PASS`) |
| installment | échéance | قسط | One payment of a pass bought in several (`pass_installments`); every 30 days |
| suspended | suspendu | موقوف | A pass with an installment overdue past the grace period: its tickets read BLOCKED until it is paid |
| manifest | manifeste | — | Signed list of an event's tickets downloaded by scanners |
| K_e | clé d'événement | — | Per-event HMAC key, delivered to enrolled scanners only |
| K_t | clé de billet | — | Per-ticket key = HMAC(K_e, ticket_id), on the holder's phone |
| T0 | coup d'envoi / début | البداية | `events.starts_at` |
| used-set | — | — | Local set of (ticket_id, direction) already scanned |
| enrol QR | QR d'enrôlement | — | Back-office QR carrying the `enrol_code` that binds a scanner to the organizer (30 min) |
| fingerprint | empreinte | — | First 8 hex chars of SHA-256 of the device public key (`AB12 CD34`), matched before approval |
| canonical scan | entrée retenue | — | Per ticket, the admitted (OK/OVERRIDE) scan with the earliest device_time; counts as the entry |
| pay code | code de paiement | رمز الأداء | `JMH 4F7K 29`, paid in cash at a guichet or agency |
| till session | caisse | الصندوق | one cashier's shift; expected vs declared cash |
| guichet | guichet | الشباك | club point of sale (back-office POS page) |
| collector | encaisseur | — | who took the cash: POS or a network |
| bank deposit | dépôt | إيداع بنكي | — |
| refund | remboursement | استرجاع | — |
| transfer | transfert | تحويل | The ticket is cancelled and a new one is issued to the recipient (new QR) |
| re-issue | réémission | إعادة الإصدار | A new ticket for the same holder after a lost phone |
| member | membre | منخرط | A fan who joined the organizer's club (free to join; the cotisation may be paid) |
| member number | n° de membre | رقم المنخرط | — |
| membership fee | cotisation | رسوم العضوية | Paid membership (a `MEMBERSHIP` product) |
| up to date | à jour | مسدَّد | A member whose membership fee is paid (`paid_until` in the future) |
| tier | niveau | المستوى | From events attended |
| split pay | paiement partagé | الدفع المشترك | Each friend pays their own place |
| campaign | campagne | حملة | — |
| gate load | affluence | الازدحام | Fluide / Modérée / Chargée |
| opt-in | consentement | الموافقة | — |
| follow | suivre | تابع | A club whose events the fan sees first ("Mes clubs"); kept on the device |
| club directory | annuaire des clubs | لائحة الفرق | The listed organizers (with a slug) |
| waiting room | file d'attente | طابور الانتظار | fans join before the sale, then come in turn |
| draw | tirage au sort | القرعة | random order of everyone who joined before the sale opened |
| position | position | ترتيبك | — |
| your turn | ton tour | دورك | 10 min to reserve |
| admission token | jeton d'accès | رمز الدخول | Internal (never shown to fans) |
| verified fan | fan vérifié | مشجع موثّق | meets this event's rule (account age, membership, matches attended) |
| pre-queue | pré-file | قبل الفتح | — |
| waitlist | liste d'attente | قائمة الانتظار | sold out: we tell you when places come back |
| presale | prévente | البيع المسبق | — |
| sale phase | phase de vente | مرحلة البيع | — |
| general sale | vente générale | البيع العام | — |
| members price | tarif membres | سعر الأعضاء | for members who joined before the sale opened |
| accreditation | accréditation | اعتماد | a free named ticket for press, staff or partners |
| invitation | invitation | دعوة | — |
| promo code | code promo | رمز التخفيض | — |
| discount | remise | تخفيض | — |

## Scan result codes

| Code | Screen word (FR) | Colour | Meaning |
| --- | --- | --- | --- |
| OK | VALIDE | green | Admitted |
| DUPLICATE | DÉJÀ UTILISÉ | red | Already entered (time + gate shown) |
| DUPLICATE_LATE | — | — | Derived in reports: a later admitted scan of an already admitted ticket. Never stored. |
| BAD_SIG | NON VALIDE | red | HMAC mismatch |
| BAD_FORMAT | ILLISIBLE | red | Not a Jomhor token |
| UNKNOWN | INCONNU | red | Not in the manifest |
| CANCELLED | ANNULÉ | red | Ticket cancelled / refunded |
| BLOCKED | BLOQUÉ | red | Blocked by the organizer |
| WRONG_ZONE | MAUVAISE PORTE | amber | Valid ticket, wrong gate; correct gates shown |
| WRONG_EVENT | AUTRE ÉVÉNEMENT | red | Token for another event |
| EXPIRED_SLOT | CODE EXPIRÉ | amber | Rotating QR outside ±1 slot; ask fan to refresh |
| OVERRIDE | DÉROGATION | blue | Supervisor let the person in; reason logged |

Override reasons (`override_reason`, B5 and CSV labels): `ID_CHECKED` Identité vérifiée · `DEVICE_FAULT` Panne d'appareil · `DAMAGED_TICKET` Billet abîmé · `OTHER` Autre motif.

Fan app entry state (no new code): "Tu es dedans" / "راك داخل" / "You're in".

## Order statuses

RESERVED (8 min hold) → PAID · RESERVED → AWAITING_CASH (cash code issued; held until least(24 h, sales close)) → PAID · EXPIRED · CANCELLED · REFUNDED (whole-order refund, Lot 5; ticket refunds keep the order PAID).

Payment methods (FR UI): CARD Carte bancaire · WALLET Wallet mobile · CASH_AGENCY Cash en agence · POS_CASH Espèces au guichet · FREE Gratuit.
