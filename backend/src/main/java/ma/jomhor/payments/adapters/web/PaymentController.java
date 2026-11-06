package ma.jomhor.payments.adapters.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.payments.application.CardGateway.Checkout;
import ma.jomhor.payments.application.CardGateway.HttpCallback;
import ma.jomhor.payments.application.PaymentService;
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

    enum Method { CARD, CASH_AGENCY, WALLET }

    record PayRequest(@NotNull Method method) {}

    record PayStarted(UUID paymentId, String redirectUrl) {}

    @PostMapping("/fan/orders/{id}/pay")
    PayStarted pay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody PayRequest body) {
        if (body.method() != Method.CARD) {
            throw new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                    Map.of("fields", Map.of("method", "only CARD is available for now")));
        }
        var started = payments.payByCard(UUID.fromString(jwt.getSubject()), id);
        return new PayStarted(started.paymentId(), started.redirectUrl());
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

    /** Browser landing after the gateway. Never reads or changes anything: the callback is the only truth. */
    @RequestMapping(path = "/payments/card/{paymentId}/return", method = {RequestMethod.GET, RequestMethod.POST})
    ResponseEntity<String> landing(@PathVariable UUID paymentId) {
        return Pages.html(HttpStatus.OK, "Paiement en cours de confirmation",
                "<h1>Paiement en cours de confirmation</h1>"
                        + "<p>Votre paiement est en cours de confirmation. Vos billets apparaîtront dès qu'il sera validé.</p>");
    }

    @PostMapping("/payments/{provider}/callback")
    ResponseEntity<String> callback(@PathVariable String provider, @RequestParam Map<String, String> params) {
        return payments.callback(provider, new HttpCallback(params));
    }
}
