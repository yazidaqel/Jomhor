package ma.jomhor.payments.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.application.PaymentMethods;
import ma.jomhor.sales.application.CashInstructions;
import ma.jomhor.tenancy.application.OrganizerService;
import ma.jomhor.tenancy.domain.CashPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Which methods an organizer's fans can use, and the cash settings (docs/04-api.md §Cash codes, plan Q45-Q47, Q54):
 * the one place that reads payments.cash.* and the organizer's cash points, for catalog (payment_methods), sales
 * (the order's cash block) and /pay.
 */
@Service
public class PaymentOptions implements PaymentMethods, CashInstructions {

    private final Payments payments;
    private final OrganizerService organizers;
    private final Duration minWindow;
    private final int maxHeldPercent;

    public PaymentOptions(Payments payments, OrganizerService organizers,
                          @Value("${payments.cash.min-window}") Duration minWindow,
                          @Value("${payments.cash.max-held-percent}") int maxHeldPercent) {
        if (maxHeldPercent < 0 || maxHeldPercent > 100) {
            throw new IllegalStateException("payments.cash.max-held-percent must be 0 to 100");
        }
        this.payments = payments;
        this.organizers = organizers;
        this.minWindow = minWindow;
        this.maxHeldPercent = maxHeldPercent;
    }

    /** payments.cash.min-window: a code must stay payable at least this long (default 2 h). */
    public Duration minWindow() {
        return minWindow;
    }

    /** payments.cash.max-held-percent: live cash holds of an event, as a share of its quota (default 20). */
    public int maxHeldPercent() {
        return maxHeldPercent;
    }

    // ponytail: no wallet provider and no cash network until BE-24 (payments.wallet.provider, the CashNetwork port).
    public boolean walletConfigured() {
        return false;
    }

    public List<String> cashNetworks() {
        return List.of();
    }

    /** Cash is offered when the organizer has a cash point or a network is enabled. */
    public boolean cashConfigured(UUID org) {
        return !cashNetworks().isEmpty() || !organizers.cashPoints(org).isEmpty();
    }

    @Override
    public Options available(UUID org, Instant salesCloseAt, Instant now) {
        List<String> methods = new ArrayList<>(List.of("CARD"));
        if (walletConfigured()) {
            methods.add("WALLET");
        }
        Instant cashUntil = salesCloseAt != null && cashConfigured(org) ? salesCloseAt.minus(minWindow) : null;
        if (cashUntil != null && now.isBefore(cashUntil)) {
            methods.add("CASH_AGENCY");
        }
        return new Options(methods, cashUntil);
    }

    @Override
    public Optional<Cash> pending(UUID org, UUID orderId) {
        return payments.pendingCode(org, orderId).map(p -> {
            List<CashPoint> points = organizers.cashPoints(org);
            return new Cash(p.payCode(), p.amountMad(), points, cashNetworks());
        });
    }
}
