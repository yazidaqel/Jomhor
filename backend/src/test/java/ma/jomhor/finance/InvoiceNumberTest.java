package ma.jomhor.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.IntegrationTest;
import ma.jomhor.finance.application.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BE-71: invoice numbers (docs/04-api.md §Finance Invoice, plan lot-5 Q229) are taken in the issuing transaction, so a
 * failure after numbering leaves no gap, and the year is the issue date's. The job serves every activated organizer:
 * earlier test classes' organizers lose their finance settings first (as MultiInstanceTest pushes leftovers away), and
 * the years used here (2040 to 2043) are this class's alone.
 */
class InvoiceNumberTest extends IntegrationTest {

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");

    @Autowired
    SettlementService service;

    private static Instant local(String at) {
        return LocalDateTime.parse(at).atZone(CASABLANCA).toInstant();
    }

    @BeforeEach
    void onlyThisClassIsActivated() {
        jdbc.sql("update organizers set settings = settings - 'finance' where settings -> 'finance' is not null")
                .update();
    }

    private UUID club(String startOn, String... paidAt) {
        UUID org = organizer("Club " + UUID.randomUUID());
        Ledger.activate(jdbc, org, 500, startOn);
        int event = Ledger.event(jdbc, org, "Match");
        for (String at : paidAt) {
            Ledger.pay(jdbc, org, Ledger.eventOrder(jdbc, org, event, "PAID"), "CARD", "FAKE", 1_000_00, local(at));
        }
        return org;
    }

    private List<Map<String, Object>> invoices(UUID org) {
        return jdbc.sql("""
                        select i.year, i.seq, i.number, i.issued_on::text as issued_on, s.day::text as day
                        from invoices i join settlements s on s.id = i.settlement_id
                        where i.organizer_id = ? order by i.seq""")
                .param(org).query().listOfRows();
    }

    private int maxSeq(int year) {
        return jdbc.sql("select coalesce(max(seq), 0) from invoices where year = ?").param(year).query(Integer.class)
                .single();
    }

    @Test
    void aFailureAfterNumberingRollsBackAndTheNextStatementTakesTheSameNumber() {
        UUID org = club("2040-03-01", "2040-03-10T12:00");
        UUID before = club("2040-03-01", "2040-03-09T12:00"); // issued first: the year's sequence has started
        service.runAt(local("2040-03-10T01:30")); // only the 9th is due
        int start = maxSeq(2040);
        assertThat(invoices(before)).extracting(r -> r.get("seq")).containsExactly(start);

        // the audit row, written after the invoice in the same transaction, fails
        jdbc.sql("""
                create function test_fail_settlement_issue() returns trigger language plpgsql as $$
                begin raise exception 'forced failure'; end $$""").update();
        jdbc.sql("""
                create trigger test_fail_settlement_issue before insert on audit_log for each row
                when (new.action = 'settlement.issue') execute function test_fail_settlement_issue()""").update();
        try {
            service.runAt(local("2040-03-11T01:00"));
        } finally {
            jdbc.sql("drop trigger test_fail_settlement_issue on audit_log").update();
            jdbc.sql("drop function test_fail_settlement_issue()").update();
        }
        assertThat(jdbc.sql("select count(*) from settlements where organizer_id = ?").param(org)
                .query(Integer.class).single()).isZero();
        assertThat(maxSeq(2040)).isEqualTo(start);

        service.runAt(local("2040-03-11T01:00"));
        assertThat(invoices(org)).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("seq", start + 1).containsEntry("number", "JMH-2040-%06d".formatted(start + 1))
                .containsEntry("day", "2040-03-10"));
    }

    @Test
    void theThirtyFirstOfDecemberIssuedOnTheFirstOfJanuaryOpensTheNewYear() {
        UUID org = club("2042-12-01", "2042-12-30T12:00", "2042-12-31T23:00");
        service.runAt(local("2042-12-31T01:00")); // the 30th, issued in 2042
        service.runAt(local("2043-01-01T01:00")); // the 31st, issued in 2043
        List<Map<String, Object>> rows = invoices(org);
        assertThat(rows).extracting(r -> r.get("day")).containsExactlyInAnyOrder("2042-12-30", "2042-12-31");
        assertThat(rows).filteredOn(r -> r.get("day").equals("2042-12-30")).singleElement().satisfies(r ->
                assertThat(r).containsEntry("number", "JMH-2042-000001").containsEntry("issued_on", "2042-12-31"));
        assertThat(rows).filteredOn(r -> r.get("day").equals("2042-12-31")).singleElement().satisfies(r ->
                assertThat(r).containsEntry("number", "JMH-2043-000001").containsEntry("issued_on", "2043-01-01")
                        .containsEntry("seq", 1));
    }
}
