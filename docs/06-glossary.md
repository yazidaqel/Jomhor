# Glossary

| Code / term | FR (UI) | AR | Meaning |
| --- | --- | --- | --- |
| Jomhor | Jomhor | جمهور | Product name. Arabic/Darija: "the crowd", the fans — the 12th man |
| organizer | organisateur | المنظم | Tenant: club, promoter, venue |
| event | événement / match | الحدث / المباراة | One ticketed occurrence; `event_type` says what kind |
| event_series | saison / édition | الموسم | Groups events; passes attach here |
| zone | tribune / zone | المدرج | Sector of a venue; `zone_id` travels in the QR |
| gate | porte | البوابة | Physical entrance; serves one or more zones |
| pass | abonnement | الاشتراك | Season ticket, materialised as one ticket per event |
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
| refund | remboursement | استرجاع | — |
| transfer | transfert | تحويل | The ticket is cancelled and a new one is issued to the recipient (new QR) |
| re-issue | réémission | إعادة الإصدار | A new ticket for the same holder after a lost phone |
| member | membre | منخرط | A fan who joined the organizer's club (free) |
| member number | n° de membre | رقم المنخرط | — |
| tier | niveau | المستوى | From events attended |
| split pay | paiement partagé | الدفع المشترك | Each friend pays their own place |
| campaign | campagne | حملة | — |
| gate load | affluence | الازدحام | Fluide / Modérée / Chargée |
| opt-in | consentement | الموافقة | — |
| follow | suivre | تابع | A club whose events the fan sees first ("Mes clubs"); kept on the device |
| club directory | annuaire des clubs | لائحة الفرق | The listed organizers (with a slug) |

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
