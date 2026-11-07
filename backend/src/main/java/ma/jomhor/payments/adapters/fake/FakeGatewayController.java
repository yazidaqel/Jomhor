package ma.jomhor.payments.adapters.fake;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import ma.jomhor.payments.adapters.web.Pages;
import ma.jomhor.payments.application.CardGateway.HttpCallback;
import ma.jomhor.payments.application.PaymentService;
import ma.jomhor.payments.domain.Payment;
import ma.jomhor.sales.domain.Order.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulated gateway's hosted page (docs/04-api.md GET /payments/fake/{payment_id}), "Wallet simulé" for a WALLET
 * attempt. "Approuver" and "Refuser"
 * post a signed callback to /payments/fake/callback, whose answer lands the browser on /return. "Réponse tardive"
 * sends the approval server-side after payments.fake.late-delay (default 21 min: past the 20 min the expiry waits
 * for a PENDING payment, so the late-success path of plan Q8 runs) and lands on /return at once.
 */
@RestController
@Profile({"dev", "test", "staging"})
@ConditionalOnProperty(name = "payments.card.provider", havingValue = "fake")
class FakeGatewayController {

    private static final Logger log = LoggerFactory.getLogger(FakeGatewayController.class);

    private final PaymentService payments;
    private final FakeCardGateway gateway;
    private final TaskScheduler scheduler;
    private final Duration lateDelay;
    private final SecureRandom random = new SecureRandom();

    FakeGatewayController(PaymentService payments, FakeCardGateway gateway, TaskScheduler scheduler,
                          @Value("${payments.fake.late-delay:21m}") Duration lateDelay) {
        this.payments = payments;
        this.gateway = gateway;
        this.scheduler = scheduler;
        this.lateDelay = lateDelay;
    }

    @GetMapping("/payments/fake/{paymentId}")
    ResponseEntity<String> page(@PathVariable UUID paymentId) {
        Payment p = payments.hosted(paymentId);
        if (p.status() != Payment.Status.PENDING) {
            return gone();
        }
        String ref = providerRef();
        boolean wallet = p.method() == PaymentMethod.WALLET; // one gateway serves both (plan Q47)
        String title = wallet ? "Wallet simulé" : "Paiement simulé";
        return Pages.html(HttpStatus.OK, title, """
                <h1>%s</h1>
                <p>Passerelle de test : %s</p>
                <p>Montant : <strong>%s</strong></p>
                %s
                %s
                <form method="post" action="%s/late"><button type="submit">Réponse tardive (approuver dans %s)</button></form>
                """.formatted(title, wallet ? "aucun wallet n'est débité." : "aucune carte n'est débitée.",
                Pages.dirhams(p.amountMad()),
                form(gateway.callback(p.id(), FakeCardGateway.APPROVED, p.amountMad(), ref), "Approuver"),
                form(gateway.callback(p.id(), FakeCardGateway.DECLINED, p.amountMad(), ref), "Refuser"),
                p.id(), duration(lateDelay)));
    }

    /** Schedules the signed approval (through the same callback path), then sends the browser to /return. */
    @PostMapping("/payments/fake/{paymentId}/late")
    ResponseEntity<?> late(@PathVariable UUID paymentId) {
        Payment p = payments.hosted(paymentId);
        if (p.status() != Payment.Status.PENDING) { // nothing to approve: no task on the shared scheduler
            return gone();
        }
        HttpCallback approval = gateway.callback(p.id(), FakeCardGateway.APPROVED, p.amountMad(), providerRef());
        scheduler.schedule(() -> {
            try {
                payments.callback("fake", approval);
            } catch (RuntimeException e) {
                log.warn("Late simulated callback for payment {} failed", p.id(), e);
            }
        }, Instant.now().plus(lateDelay));
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(payments.baseUrl() + "/payments/card/" + p.id() + "/return")).build();
    }

    private static ResponseEntity<String> gone() {
        return Pages.html(HttpStatus.GONE, "Paiement simulé",
                "<h1>Paiement simulé</h1><p>Ce paiement n'est plus en attente.</p>");
    }

    // Field values are a UUID, digits, an enum word and hex: nothing to escape.
    private static String form(HttpCallback callback, String label) {
        StringBuilder fields = new StringBuilder();
        callback.params().forEach((k, v) -> fields.append("<input type=\"hidden\" name=\"%s\" value=\"%s\">\n".formatted(k, v)));
        return "<form method=\"post\" action=\"callback\">\n" + fields + "<button type=\"submit\">" + label + "</button></form>";
    }

    private String providerRef() {
        byte[] b = new byte[8];
        random.nextBytes(b);
        return "FAKE-" + HexFormat.of().formatHex(b);
    }

    private static String duration(Duration d) {
        return d.toSeconds() < 120 ? d.toSeconds() + " s" : d.toMinutes() + " min";
    }
}
