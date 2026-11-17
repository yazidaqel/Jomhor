package ma.jomhor.finance.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * A stored fee invoice (invoices) with the statement it bills: what the PDF prints, nothing read from today's config
 * or settings. {@code issuer} and {@code customer} are the snapshots copied in at issue (snake_case keys). Money in
 * centimes; {@code baseMad} = the statement's collected + till.
 */
public record Invoice(UUID id, String number, LocalDate issuedOn, Map<String, String> issuer,
                      Map<String, String> customer, LocalDate day, long baseMad, int feeRateBp, long amountHtMad,
                      int vatRateBp, long vatMad, long amountTtcMad) {}
