package ma.jomhor.payments.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ma.jomhor.payments.application.Payments;
import ma.jomhor.payments.domain.Payment;
import ma.jomhor.sales.application.PendingPayments;
import ma.jomhor.sales.domain.Order.PaymentMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** payments, and the sales PendingPayments port (dependency inversion: sales never reads this table). */
@Repository
class JdbcPayments implements Payments, PendingPayments {

    private static final String COLUMNS =
            "id, organizer_id, order_id, method, provider, amount_mad, status, pay_code, pos_session_id";
    /** The attempt an order reports (04 GET /fan/orders/{id}): a success first, else the latest. */
    private static final String REPORTED_FIRST = "status = 'SUCCEEDED' desc, created_at desc, id desc";

    private final JdbcClient jdbc;

    JdbcPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Payment insertPending(UUID org, UUID orderId, PaymentMethod method, String provider, long amountMad) {
        return jdbc.sql("""
                        insert into payments (organizer_id, order_id, method, provider, amount_mad)
                        values (:org, :order, cast(:method as payment_method), :provider, :amount)
                        returning\s""" + COLUMNS)
                .param("org", org).param("order", orderId).param("method", method.name()).param("provider", provider)
                .param("amount", amountMad)
                .query(JdbcPayments::payment).single();
    }

    // on conflict: a taken code answers empty instead of aborting the caller's transaction (04 §Cash codes Stored).
    @Override
    public Optional<Payment> insertCashPending(UUID org, UUID orderId, String payCode, long amountMad) {
        return jdbc.sql("""
                        insert into payments (organizer_id, order_id, method, provider, amount_mad, pay_code)
                        values (:org, :order, 'CASH_AGENCY', 'CASH', :amount, :code)
                        on conflict (pay_code) do nothing
                        returning\s""" + COLUMNS)
                .param("org", org).param("order", orderId).param("amount", amountMad).param("code", payCode)
                .query(JdbcPayments::payment).optional();
    }

    @Override
    public Optional<Payment> pendingCode(UUID org, UUID orderId) {
        return jdbc.sql("select " + COLUMNS + """
                         from payments
                        where organizer_id = :org and order_id = :order and method = 'CASH_AGENCY' and status = 'PENDING'
                        order by created_at desc limit 1
                        """)
                .param("org", org).param("order", orderId).query(JdbcPayments::payment).optional();
    }

    @Override
    public void insertPos(UUID org, UUID orderId, long amountMad, UUID sessionId) {
        jdbc.sql("""
                        insert into payments (organizer_id, order_id, method, provider, amount_mad, status, pos_session_id,
                                              completed_at)
                        values (:org, :order, 'POS_CASH', 'POS', :amount, 'SUCCEEDED', :session, now())
                        """)
                .param("org", org).param("order", orderId).param("amount", amountMad).param("session", sessionId)
                .update();
    }

    @Override
    public Optional<Payment> findCode(UUID org, String payCode) {
        return jdbc.sql("select " + COLUMNS + " from payments where organizer_id = :org and pay_code = :code")
                .param("org", org).param("code", payCode).query(JdbcPayments::payment).optional();
    }

    @Override
    public Optional<Payment> lockCode(UUID org, String payCode) {
        return jdbc.sql("select " + COLUMNS + " from payments where organizer_id = :org and pay_code = :code for update")
                .param("org", org).param("code", payCode).query(JdbcPayments::payment).optional();
    }

    // A system read across organizers (pay_code is unique forever), like find(paymentId).
    @Override
    public Optional<Payment> findCode(String payCode) {
        return jdbc.sql("select " + COLUMNS + " from payments where pay_code = :code")
                .param("code", payCode).query(JdbcPayments::payment).optional();
    }

    @Override
    public Optional<Payment> lockCode(String payCode) {
        return jdbc.sql("select " + COLUMNS + " from payments where pay_code = :code for update")
                .param("code", payCode).query(JdbcPayments::payment).optional();
    }

    @Override
    public Optional<UUID> withRef(String provider, String providerRef) {
        return jdbc.sql("select id from payments where provider = :provider and provider_ref = :ref")
                .param("provider", provider).param("ref", providerRef).query(UUID.class).optional();
    }

    @Override
    public void collect(UUID org, UUID paymentId, UUID sessionId) {
        jdbc.sql("""
                        update payments set status = 'SUCCEEDED', provider = 'POS', pos_session_id = :session,
                          completed_at = now()
                        where organizer_id = :org and id = :id and status = 'PENDING'
                        """)
                .param("org", org).param("id", paymentId).param("session", sessionId).update();
    }

    @Override
    public Optional<UUID> tillSession(UUID org, UUID orderId) {
        return jdbc.sql("""
                        select pos_session_id from payments
                        where organizer_id = :org and order_id = :order and status = 'SUCCEEDED' and pos_session_id is not null
                        order by completed_at limit 1
                        """)
                .param("org", org).param("order", orderId).query(UUID.class).optional();
    }

    @Override
    public Optional<Payment> find(UUID paymentId) {
        return jdbc.sql("select " + COLUMNS + " from payments where id = :id")
                .param("id", paymentId).query(JdbcPayments::payment).optional();
    }

    @Override
    public Optional<Payment> lock(UUID paymentId) {
        return jdbc.sql("select " + COLUMNS + " from payments where id = :id for update")
                .param("id", paymentId).query(JdbcPayments::payment).optional();
    }

    @Override
    public void markRedirected(UUID org, UUID paymentId) {
        jdbc.sql("""
                        update payments set redirected_at = now()
                        where organizer_id = :org and id = :id and status = 'PENDING' and redirected_at is null
                        """)
                .param("org", org).param("id", paymentId).update();
    }

    @Override
    public void complete(UUID org, UUID paymentId, Payment.Status status, String provider, String providerRef,
                         String callbackJson) {
        jdbc.sql("""
                        update payments set status = cast(:status as payment_status), provider = :provider, provider_ref = :ref,
                          callback = cast(:callback as jsonb), completed_at = now()
                        where organizer_id = :org and id = :id and status = 'PENDING'
                        """)
                .param("org", org).param("id", paymentId).param("status", status.name()).param("provider", provider)
                .param("ref", providerRef)
                .param("callback", callbackJson).update();
    }

    // A system read across organizers, like the expiry it serves.
    // ponytail: a cross-tenant scan of PENDING payments twice per expiry batch, with no index on
    // payments (status, redirected_at); add a partial index (where status = 'PENDING') when volume matters.
    @Override
    public Set<UUID> ordersRedirectedWithin(Duration age) {
        return new HashSet<>(jdbc.sql("""
                        select distinct order_id from payments
                        where status = 'PENDING' and method in ('CARD', 'WALLET')
                          and redirected_at > now() - make_interval(secs => :secs)
                        """)
                .param("secs", age.toSeconds()).query(UUID.class).list());
    }

    @Override
    public Optional<Attempt> latestAttempt(UUID org, UUID orderId, Duration pendingFailsAfter) {
        return jdbc.sql("""
                        select method::text as method,
                               case when status = 'PENDING' and method in ('CARD', 'WALLET')
                                         and coalesce(redirected_at, created_at) <= now() - make_interval(secs => :secs)
                                    then 'FAILED' else status::text end as status
                        from payments where organizer_id = :org and order_id = :order
                        order by\s""" + REPORTED_FIRST + " limit 1")
                .param("org", org).param("order", orderId).param("secs", pendingFailsAfter.toSeconds())
                .query((rs, n) -> new Attempt(PaymentMethod.valueOf(rs.getString("method")),
                        AttemptStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    @Override
    public Map<UUID, PaymentMethod> latestMethods(Map<UUID, UUID> organizerByOrder) {
        Map<UUID, PaymentMethod> methods = new HashMap<>();
        if (organizerByOrder.isEmpty()) {
            return methods;
        }
        UUID[] orders = organizerByOrder.keySet().toArray(UUID[]::new);
        UUID[] orgs = new UUID[orders.length];
        for (int i = 0; i < orders.length; i++) {
            orgs[i] = organizerByOrder.get(orders[i]);
        }
        jdbc.sql("""
                        select distinct on (order_id) order_id, method::text as method from payments
                        where (organizer_id, order_id) in (select * from unnest(cast(:orgs as uuid[]), cast(:orders as uuid[])))
                        order by order_id,\s""" + REPORTED_FIRST)
                .param("orgs", orgs).param("orders", orders)
                .query((rs, n) -> Map.entry(rs.getObject("order_id", UUID.class),
                        PaymentMethod.valueOf(rs.getString("method"))))
                .list().forEach(e -> methods.put(e.getKey(), e.getValue()));
        return methods;
    }

    @Override
    public Map<UUID, String> pendingCodes(Map<UUID, UUID> organizerByOrder) {
        Map<UUID, String> codes = new HashMap<>();
        if (organizerByOrder.isEmpty()) {
            return codes;
        }
        UUID[] orders = organizerByOrder.keySet().toArray(UUID[]::new);
        UUID[] orgs = new UUID[orders.length];
        for (int i = 0; i < orders.length; i++) {
            orgs[i] = organizerByOrder.get(orders[i]);
        }
        jdbc.sql("""
                        select distinct on (order_id) order_id, pay_code from payments
                        where (organizer_id, order_id) in (select * from unnest(cast(:orgs as uuid[]), cast(:orders as uuid[])))
                          and method = 'CASH_AGENCY' and status = 'PENDING'
                        order by order_id, created_at desc
                        """)
                .param("orgs", orgs).param("orders", orders)
                .query((rs, n) -> Map.entry(rs.getObject("order_id", UUID.class), rs.getString("pay_code")))
                .list().forEach(e -> codes.put(e.getKey(), e.getValue()));
        return codes;
    }

    private static Payment payment(ResultSet rs, int n) throws SQLException {
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
                rs.getObject("order_id", UUID.class), PaymentMethod.valueOf(rs.getString("method")),
                rs.getString("provider"), rs.getLong("amount_mad"), Payment.Status.valueOf(rs.getString("status")),
                rs.getString("pay_code"), rs.getObject("pos_session_id", UUID.class));
    }
}
