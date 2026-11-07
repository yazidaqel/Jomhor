package ma.jomhor.payments.adapters.fakecash;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import ma.jomhor.payments.adapters.web.Pages;
import ma.jomhor.payments.application.PosService;
import ma.jomhor.payments.domain.CashCode;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The simulated network's agent terminal (docs/04-api.md GET, POST /payments/cash/fake). The agent types a code; a
 * payable one shows the event title and amount only (04 §Cash codes Reveals: never a name or phone) and an
 * "Encaisser" form whose hidden fields are the signed callback, posted to /payments/cash/fake/callback.
 */
@RestController
@Profile({"dev", "test", "staging"})
@Conditional(FakeCashNetwork.Listed.class)
class FakeCashTerminal {

    private static final String TITLE = "Terminal agent simulé";
    private static final String FORM = """
            <form method="post" action="fake">
            <label>Code de paiement <input name="code" autocomplete="off" required></label>
            <button type="submit">Chercher</button></form>
            """;

    private final PosService pos;
    private final FakeCashNetwork network;
    private final SecureRandom random = new SecureRandom();

    FakeCashTerminal(PosService pos, FakeCashNetwork network) {
        this.pos = pos;
        this.network = network;
    }

    @GetMapping("/payments/cash/fake")
    ResponseEntity<String> terminal() {
        return Pages.html(HttpStatus.OK, TITLE, "<h1>" + TITLE + "</h1>\n" + FORM);
    }

    @PostMapping("/payments/cash/fake")
    ResponseEntity<String> lookup(@RequestParam(defaultValue = "") String code) {
        Optional<PosService.Lookup> found = pos.networkLookup(code).filter(PosService.Lookup::payable);
        if (found.isEmpty()) {
            return Pages.html(HttpStatus.OK, TITLE,
                    "<h1>" + TITLE + "</h1>\n<p>Code inconnu ou non payable</p>\n" + FORM);
        }
        PosService.Lookup l = found.get();
        String payCode = CashCode.normalise(code).orElseThrow();
        return Pages.html(HttpStatus.OK, TITLE, """
                <h1>%s</h1>
                <p>Événement : <strong>%s</strong></p>
                <p>Montant : <strong>%s</strong></p>
                %s
                <p><a href="fake">Annuler</a></p>
                """.formatted(TITLE, HtmlUtils.htmlEscape(l.event().title()), Pages.dirhams(l.amountMad()),
                encaisser(payCode, l.amountMad())));
    }

    // Field values are base32 symbols, digits and hex: nothing to escape.
    private String encaisser(String payCode, long amountMad) {
        byte[] b = new byte[8];
        random.nextBytes(b);
        String ref = "FAKECASH-" + HexFormat.of().formatHex(b);
        String amount = Long.toString(amountMad);
        return """
                <form method="post" action="fake/callback">
                <input type="hidden" name="pay_code" value="%s">
                <input type="hidden" name="amount_mad" value="%s">
                <input type="hidden" name="network_ref" value="%s">
                <input type="hidden" name="signature" value="%s">
                <button type="submit">Encaisser</button></form>
                """.formatted(payCode, amount, ref, network.sign(payCode, amount, ref));
    }
}
