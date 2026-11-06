package ma.jomhor.payments.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ma.jomhor.payments.application.Payments;
import ma.jomhor.payments.domain.Payment;
import ma.jomhor.sales.application.PendingPayments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** payments, and the sales PendingPayments port (dependency inversion: sales never reads this table). */
@Repository
class JdbcPayments implements Payments, PendingPayments {

    private static final String COLUMNS = "id, organizer_id, order_id, provider, amount_mad, status";

    private final JdbcClient jdbc;

    JdbcPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Payment insertPending(UUID org, UUID orderId, String provider, long amountMad) {
        return jdbc.sql("""
                        insert into payments (organizer_id, order_id, method, provider, amount_mad)
                        values (:org, :order, 'CARD', :provider, :amount)
                        returning\s""" + COLUMNS)
                .param("org", org).param("order", orderId).param("provider", provider).param("amount", amountMad)
                .query(JdbcPayments::payment).single();
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
    public void complete(UUID org, UUID paymentId, Payment.Status status, String providerRef, String callbackJson) {
        jdbc.sql("""
                        update payments set status = cast(:status as payment_status), provider_ref = :ref,
                          callback = cast(:callback as jsonb), completed_at = now()
                        where organizer_id = :org and id = :id and status = 'PENDING'
                        """)
                .param("org", org).param("id", paymentId).param("status", status.name()).param("ref", providerRef)
                .param("callback", callbackJson).update();
    }

    // A system read across organizers, like the expiry it serves.
    // ponytail: a cross-tenant scan of PENDING payments twice per expiry batch, with no index on
    // payments (status, created_at); add a partial index (where status = 'PENDING') when volume matters.
    @Override
    public Set<UUID> ordersWithPendingYoungerThan(Duration age) {
        return new HashSet<>(jdbc.sql("""
                        select distinct order_id from payments
                        where status = 'PENDING' and created_at > now() - make_interval(secs => :secs)
                        """)
                .param("secs", age.toSeconds()).query(UUID.class).list());
    }

    private static Payment payment(ResultSet rs, int n) throws SQLException {
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getString("provider"), rs.getLong("amount_mad"),
                Payment.Status.valueOf(rs.getString("status")));
    }
}
