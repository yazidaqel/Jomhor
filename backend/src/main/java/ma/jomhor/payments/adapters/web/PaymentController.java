package ma.jomhor.payments.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.jomhor.payments.application.CardGateway.Checkout;
import ma.jomhor.payments.application.CardGateway.HttpCallback;
import ma.jomhor.payments.application.PaymentService;
import ma.jomhor.sales.domain.Order.PaymentMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/** docs/04-api.md: POST /fan/orders/{id}/pay, and §Payments redirect, return and callback. */
@RestController
class PaymentController {

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** What a fan can ask for (POS_CASH and FREE are not fan methods: 422). */
    enum Method { CARD, CASH_AGENCY, WALLET }

    record PayRequest(@NotNull Method method) {}

    record HostedDto(UUID paymentId, PaymentMethod method, String redirectUrl) {}

    /** {@code pay_code}: the 6 stored symbols; clients show it as {@code JMH 4F7K 29} (04 §Cash codes Display). */
    record CashDto(UUID paymentId, PaymentMethod method, String payCode, Instant reservedUntil, long amountMad) {}

    @PostMapping("/fan/orders/{id}/pay")
    Object pay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody PayRequest body) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (body.method() == Method.CASH_AGENCY) {
            var c = payments.payCash(userId, id);
            return new CashDto(c.paymentId(), PaymentMethod.CASH_AGENCY, c.payCode(), c.reservedUntil(), c.amountMad());
        }
        var started = payments.payHosted(userId, id, PaymentMethod.valueOf(body.method().name()));
        return new HostedDto(started.paymentId(), started.method(), started.redirectUrl());
    }

    /** 302 to the gateway, or an auto-submitting form when the gateway wants a POST. */
    @GetMapping("/payments/card/{paymentId}/redirect")
    ResponseEntity<String> redirect(@PathVariable UUID paymentId) {
        Checkout c = payments.checkout(paymentId);
        if (c.postFields().isEmpty()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(c.url()).header("Cache-Control", "no-store").build();
        }
        String fields = c.postFields().entrySet().stream()
                .map(e -> "<input type=\"hidden\" name=\"%s\" value=\"%s\">"
                        .formatted(HtmlUtils.htmlEscape(e.getKey()), HtmlUtils.htmlEscape(e.getValue())))
                .collect(Collectors.joining("\n"));
        return Pages.html(HttpStatus.OK, "Redirection vers le paiement", """
                <form id="go" method="post" action="%s">
                %s
                <noscript><button type="submit">Continuer vers le paiement</button></noscript>
                </form>
                <script>document.getElementById('go').submit()</script>
                """.formatted(HtmlUtils.htmlEscape(c.url().toString()), fields));
    }

    @ExceptionHandler(PaymentService.NotPending.class)
    ResponseEntity<String> gone() {
        return Pages.html(HttpStatus.GONE, "Paiement terminé",
                "<h1>Paiement terminé</h1><p>Ce paiement n'est plus en attente.</p>");
    }

    /**
     * Browser landing after the gateway: 302 to the configured page of the order's channel, which polls the order.
     * Never changes anything, and ignores whatever the gateway posts here: the callback is the only truth.
     */
    @RequestMapping(path = "/payments/card/{paymentId}/return", method = {RequestMethod.GET, RequestMethod.POST})
    ResponseEntity<String> landing(@PathVariable UUID paymentId) {
        var target = payments.returnUrl(paymentId);
        if (target.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(target.get()).header("Cache-Control", "no-store")
                    .build();
        }
        return Pages.html(HttpStatus.OK, "Paiement en cours de confirmation",
                "<h1>Paiement en cours de confirmation</h1>"
                        + "<p>Votre paiement est en cours de confirmation. Vos billets apparaîtront dès qu'il sera validé.</p>");
    }

    @PostMapping("/payments/{provider}/callback")
    ResponseEntity<String> callback(@PathVariable String provider, @RequestParam Map<String, String> params) {
        return payments.callback(provider, new HttpCallback(params));
    }

    @PostMapping("/payments/cash/{network}/callback")
    ResponseEntity<String> cashCallback(@PathVariable String network, @RequestParam Map<String, String> params) {
        return payments.cashCallback(network, new HttpCallback(params));
    }
}
