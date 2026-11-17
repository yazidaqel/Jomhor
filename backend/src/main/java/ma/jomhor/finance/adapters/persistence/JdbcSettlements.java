package ma.jomhor.finance.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.finance.application.Settlements;
import ma.jomhor.finance.domain.Settlement;
import ma.jomhor.finance.domain.Statement;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * settlements, settlement_lines and invoices. The reads join staff (the payer's name), and events and products for a
 * line's title: read-only report joins, tenant-filtered (docs/01-architecture.md).
 */
@Repository
class JdbcSettlements implements Settlements {

    /** Two-int advisory key space: (10, hashtext(organizer id)), the statement try-lock (common/EventLock). */
    private static final int STATEMENT_LOCK = 10;
    /** Single-bigint (11 << 32) | year: the invoice number lock (common/EventLock). */
    private static final long INVOICE_LOCK = 11;

    private static final String SELECT = """
            select s.id, s.day, s.status::text as status, s.collected_mad, s.till_mad, s.fee_rate_bp, s.fee_ht_mad,
                   s.fee_vat_mad, s.carried_in_mad, s.payout_mad, s.due_on, s.paid_at, s.paid_by, pb.name as paid_by_name,
                   s.payout_reference, i.id as invoice_id, i.number as invoice_number
            from settlements s
              left join staff pb on pb.id = s.paid_by and pb.organizer_id = s.organizer_id
              left join invoices i on i.settlement_id = s.id and i.organizer_id = s.organizer_id
            where s.organizer_id = :org""";

    private final JdbcClient jdbc;

    JdbcSettlements(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryLock(UUID org) {
        return jdbc.sql("select pg_try_advisory_xact_lock(?, hashtext(?))").params(STATEMENT_LOCK, org.toString())
                .query(Boolean.class).single();
    }

    @Override
    public Instant now() {
        return jdbc.sql("select clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    @Override
    public Optional<Settlement> latest(UUID org) {
        return jdbc.sql(SELECT + " order by s.day desc limit 1").param("org", org).query(JdbcSettlements::settlement)
                .optional();
    }

    @Override
    public UUID insert(UUID org, LocalDate day, Statement s, LocalDate dueOn, Instant issuedAt) {
        UUID id = jdbc.sql("""
                        insert into settlements (organizer_id, day, collected_mad, till_mad, fee_rate_bp, fee_ht_mad,
                                                 fee_vat_mad, carried_in_mad, payout_mad, due_on, status, issued_at)
                        values (:org, :day, :collected, :till, :rate, :ht, :vat, :carried, :payout, :due,
                                cast(:status as settlement_status), :issued)
                        returning id""")
                .param("org", org).param("day", day).param("collected", s.collectedMad()).param("till", s.tillMad())
                .param("rate", s.feeRateBp()).param("ht", s.feeHtMad()).param("vat", s.feeVatMad())
                .param("carried", s.carriedInMad()).param("payout", s.payoutMad()).param("due", dueOn)
                .param("status", s.status()).param("issued", OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC))
                .query(UUID.class).single();
        for (Statement.Line l : s.lines()) {
            jdbc.sql("""
                            insert into settlement_lines (organizer_id, settlement_id, event_id, product_id, method,
                                                          collector, payments, amount_mad)
                            values (:org, :settlement, :event, :product, cast(:method as payment_method), :collector,
                                    :payments, :amount)""")
                    .param("org", org).param("settlement", id).param("event", l.eventId()).param("product", l.productId())
                    .param("method", l.method()).param("collector", l.collector()).param("payments", l.payments())
                    .param("amount", l.amountMad()).update();
        }
        return id;
    }

    @Override
    public void lockInvoiceYear(int year) {
        jdbc.sql("select 1 from pg_advisory_xact_lock(?)").param((INVOICE_LOCK << 32) | year).query(Integer.class)
                .single();
    }

    @Override
    public int nextInvoiceSeq(int year) {
        return jdbc.sql("select coalesce(max(seq), 0) + 1 from invoices where year = ?").param(year)
                .query(Integer.class).single();
    }

    @Override
    public UUID insertInvoice(UUID org, UUID settlementId, int year, int seq, String number, LocalDate issuedOn,
                              String issuerJson, String customerJson, long htMad, int vatRateBp, long vatMad) {
        return jdbc.sql("""
                        insert into invoices (organizer_id, settlement_id, year, seq, number, issued_on, issuer, customer,
                                              amount_ht_mad, vat_rate_bp, vat_mad, amount_ttc_mad)
                        values (:org, :settlement, :year, :seq, :number, :on, cast(:issuer as jsonb),
                                cast(:customer as jsonb), :ht, :rate, :vat, :ttc)
                        returning id""")
                .param("org", org).param("settlement", settlementId).param("year", year).param("seq", seq)
                .param("number", number).param("on", issuedOn).param("issuer", issuerJson)
                .param("customer", customerJson).param("ht", htMad).param("rate", vatRateBp).param("vat", vatMad)
                .param("ttc", Math.addExact(htMad, vatMad))
                .query(UUID.class).single();
    }

    @Override
    public List<Settlement> between(UUID org, LocalDate from, LocalDate to) {
        return jdbc.sql(SELECT + " and s.day between :from and :to order by s.day desc").param("org", org)
                .param("from", from).param("to", to).query(JdbcSettlements::settlement).list();
    }

    @Override
    public Optional<Settlement> get(UUID org, UUID id) {
        return jdbc.sql(SELECT + " and s.id = :id").param("org", org).param("id", id)
                .query(JdbcSettlements::settlement).optional();
    }

    @Override
    public Optional<String> lockStatus(UUID org, UUID id) {
        return jdbc.sql("select status::text from settlements where organizer_id = ? and id = ? for update")
                .params(org, id).query(String.class).optional();
    }

    @Override
    public void markPaid(UUID org, UUID userId, UUID id, String reference) {
        jdbc.sql("""
                        update settlements set status = 'PAID', paid_at = now(), payout_reference = :ref,
                               paid_by = (select s.id from staff s where s.organizer_id = :org and s.user_id = :user)
                        where organizer_id = :org and id = :id and status = 'DUE'""")
                .param("org", org).param("user", userId).param("id", id).param("ref", reference).update();
    }

    @Override
    public Optional<StoredInvoice> invoice(UUID org, UUID invoiceId) {
        return jdbc.sql("""
                        select i.id, i.number, i.issued_on, i.issuer::text as issuer, i.customer::text as customer,
                               s.day, s.collected_mad + s.till_mad as base_mad, s.fee_rate_bp, i.amount_ht_mad,
                               i.vat_rate_bp, i.vat_mad, i.amount_ttc_mad
                        from invoices i join settlements s on s.id = i.settlement_id and s.organizer_id = i.organizer_id
                        where i.organizer_id = ? and i.id = ?""")
                .params(org, invoiceId)
                .query((rs, n) -> new StoredInvoice(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getObject("issued_on", LocalDate.class), rs.getString("issuer"), rs.getString("customer"),
                        rs.getObject("day", LocalDate.class), rs.getLong("base_mad"), rs.getInt("fee_rate_bp"),
                        rs.getLong("amount_ht_mad"), rs.getInt("vat_rate_bp"), rs.getLong("vat_mad"),
                        rs.getLong("amount_ttc_mad")))
                .optional();
    }

    @Override
    public long[] due(UUID org, LocalDate today) {
        return jdbc.sql("""
                        select count(*), coalesce(sum(payout_mad), 0), count(*) filter (where due_on < ?)
                        from settlements where organizer_id = ? and status = 'DUE'""")
                .params(today, org).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)})
                .single();
    }

    @Override
    public List<Settlement.Line> lines(UUID org, List<UUID> settlementIds) {
        return jdbc.sql("""
                        select l.settlement_id, l.event_id, l.product_id, coalesce(e.title, p.name) as title,
                               l.method::text as method, l.collector, l.payments, l.amount_mad
                        from settlement_lines l
                          left join events e on e.id = l.event_id and e.organizer_id = l.organizer_id
                          left join products p on p.id = l.product_id and p.organizer_id = l.organizer_id
                        where l.organizer_id = :org and l.settlement_id = any(cast(:ids as uuid[]))
                        order by title, method, l.collector, l.id""")
                .param("org", org).param("ids", settlementIds.toArray(UUID[]::new))
                .query((rs, n) -> new Settlement.Line(rs.getObject("settlement_id", UUID.class),
                        rs.getObject("event_id", Integer.class), rs.getObject("product_id", UUID.class),
                        rs.getString("title"), rs.getString("method"), rs.getString("collector"), rs.getInt("payments"),
                        rs.getLong("amount_mad")))
                .list();
    }

    private static Settlement settlement(ResultSet rs, int n) throws SQLException {
        OffsetDateTime paidAt = rs.getObject("paid_at", OffsetDateTime.class);
        return new Settlement(rs.getObject("id", UUID.class), rs.getObject("day", LocalDate.class),
                rs.getString("status"), rs.getLong("collected_mad"), rs.getLong("till_mad"), rs.getInt("fee_rate_bp"),
                rs.getLong("fee_ht_mad"), rs.getLong("fee_vat_mad"), rs.getLong("carried_in_mad"),
                rs.getLong("payout_mad"), rs.getObject("due_on", LocalDate.class),
                paidAt == null ? null : paidAt.toInstant(), rs.getObject("paid_by", UUID.class),
                rs.getString("paid_by_name"), rs.getString("payout_reference"), rs.getObject("invoice_id", UUID.class),
                rs.getString("invoice_number"));
    }
}
