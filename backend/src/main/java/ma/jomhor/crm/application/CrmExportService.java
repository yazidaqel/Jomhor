package ma.jomhor.crm.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.common.AuditLog;
import ma.jomhor.crm.application.Memberships.ExportRow;
import ma.jomhor.sales.products.application.PassService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /org/crm/export.csv and the summary's {@code exportable} (docs/04-api.md §Finance CRM export, plan lot-5 Q235):
 * personal data, minimised. Pass holders come from sales' PassService, never the passes table. Kept apart from
 * MembershipService, which sales' ProductService depends on, so the two modules' beans don't form a cycle.
 */
@Service
public class CrmExportService {

    /** Local days of the export: "today" for a series' ends_on, dates in the file, its name. */
    public static final ZoneId ZONE = ZoneId.of(Venue.DEFAULT_TIMEZONE);

    /** {@code day}: the local day of the export (the file's name). */
    public record Export(LocalDate day, List<ExportRow> rows) {}

    private final Memberships memberships;
    private final MembershipService members;
    private final PassService passes;
    private final AuditLog audit;

    CrmExportService(Memberships memberships, MembershipService members, PassService passes, AuditLog audit) {
        this.memberships = memberships;
        this.members = members;
        this.passes = passes;
        this.audit = audit;
    }

    /** The rows, by number, audited {@code crm.export {rows, with_phone}}: counts only, no name or phone. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Export export(UUID org, UUID user) {
        LocalDate today = LocalDate.now(ZONE);
        List<ExportRow> rows = memberships.exportRows(org, passes.liveHolders(org, today));
        long withPhone = rows.stream().filter(r -> r.phone() != null).count();
        audit.staff(org, user, "crm.export", null,
                "{\"rows\": %d, \"with_phone\": %d}".formatted(rows.size(), withPhone));
        return new Export(today, rows);
    }

    /** 04 GET /org/members/summary with {@code exportable}, one snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MembershipService.Summary summary(UUID org) {
        Set<UUID> holders = passes.liveHolders(org, LocalDate.now(ZONE));
        return members.summary(org, holders);
    }
}
