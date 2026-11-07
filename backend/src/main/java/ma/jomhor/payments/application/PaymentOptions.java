package ma.jomhor.payments.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.PaymentMethods;
import ma.jomhor.sales.application.CashInstructions;
import ma.jomhor.tenancy.application.OrganizerService;
import ma.jomhor.tenancy.domain.CashPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/**
 * Which methods an organizer's fans can use, and the cash settings (docs/04-api.md §Cash codes, plan Q45-Q47, Q54):
 * the one place that reads payments.cash.*, payments.wallet.provider and the organizer's cash points, for catalog
 * (payment_methods), sales (the order's cash block), /pay and the cash network callback.
 *
 * <p>Startup fails under prod with the simulated network (payments.cash.networks containing fake) or the simulated
 * wallet (payments.wallet.provider=fake), for a listed network without an adapter, and for a wallet provider that
 * isn't the card gateway's (one gateway serves both, plan Q47).
 */
@Service
public class PaymentOptions implements PaymentMethods, CashInstructions {

    private final Payments payments;
    private final OrganizerService organizers;
    private final Duration minWindow;
    private final int maxHeldPercent;
    /** The enabled networks, in payments.cash.networks order. */
    private final List<CashNetwork> networks;
    private final boolean wallet;

    public PaymentOptions(Payments payments, OrganizerService organizers,
                          @Value("${payments.cash.min-window}") Duration minWindow,
                          @Value("${payments.cash.max-held-percent}") int maxHeldPercent,
                          @Value("${payments.cash.networks:}") String networks,
                          @Value("${payments.wallet.provider:}") String walletProvider,
                          List<CashNetwork> adapters, CardGateway gateway, Environment env) {
        if (maxHeldPercent < 0 || maxHeldPercent > 100) {
            throw new IllegalStateException("payments.cash.max-held-percent must be 0 to 100");
        }
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        List<String> names = names(networks);
        if (prod && names.contains("fake")) {
            throw new IllegalStateException("The simulated cash network is refused under the prod profile");
        }
        Map<String, CashNetwork> byName = adapters.stream().collect(Collectors.toMap(CashNetwork::name, Function.identity()));
        this.networks = names.stream().map(n -> Optional.ofNullable(byName.get(n))
                .orElseThrow(() -> new IllegalStateException("No adapter for cash network " + n))).toList();
        String w = walletProvider.strip();
        if (prod && w.equalsIgnoreCase("fake")) {
            throw new IllegalStateException("The simulated wallet is refused under the prod profile");
        }
        if (!w.isEmpty() && !w.equalsIgnoreCase(gateway.provider())) {
            throw new IllegalStateException("payments.wallet.provider must be the card gateway's provider");
        }
        this.wallet = !w.isEmpty();
        this.payments = payments;
        this.organizers = organizers;
        this.minWindow = minWindow;
        this.maxHeldPercent = maxHeldPercent;
    }

    /** payments.cash.networks: comma-separated names, blanks ignored. */
    public static List<String> names(String networks) {
        return Arrays.stream(networks == null ? new String[0] : networks.split(",")).map(String::strip)
                .filter(n -> !n.isEmpty()).toList();
    }

    /** payments.cash.min-window: a code must stay payable at least this long (default 2 h). */
    public Duration minWindow() {
        return minWindow;
    }

    /** payments.cash.max-held-percent: live cash holds of an event, as a share of its quota (default 20). */
    public int maxHeldPercent() {
        return maxHeldPercent;
    }

    /** payments.wallet.provider is set (staging: fake; prod: unset until a provider exists, plan Q47). */
    public boolean walletConfigured() {
        return wallet;
    }

    /** Display names of the enabled cash networks (the order's cash.networks). */
    public List<String> cashNetworks() {
        return networks.stream().map(CashNetwork::displayName).toList();
    }

    /** The enabled network behind /payments/cash/{name}/callback. */
    public Optional<CashNetwork> network(String name) {
        return networks.stream().filter(n -> n.name().equals(name)).findFirst();
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
