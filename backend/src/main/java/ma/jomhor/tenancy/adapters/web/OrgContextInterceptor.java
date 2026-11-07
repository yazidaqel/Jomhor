package ma.jomhor.tenancy.adapters.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.UUID;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.tenancy.application.AuthService;
import ma.jomhor.tenancy.domain.OrgContext;
import ma.jomhor.tenancy.domain.StaffRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Every /org/** call except /org/auth/** and /org/me: X-Organizer-Id → active staff row of the
 * token subject → {@link OrgContext} request attribute. Lot 0 roles: ADMIN, OPS read and write;
 * FINANCE read only; GATE, SUPERVISOR 403. Lot 3: /org/pos/** is ADMIN, OPS, CASHIER; CASHIER may call nothing else
 * but GET /org/events, /org/events/{id} and /org/events/{id}/prices. FINANCE may also POST the ticket lookup and mark
 * a refund done (its first write); the controllers narrow the roles further.
 */
class OrgContextInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Organizer-Id";
    /** Handler patterns (as mapped, not the raw URI) a CASHIER may GET besides /org/pos/**. */
    private static final Set<String> CASHIER_READS =
            Set.of("/org/events", "/org/events/{id}", "/org/events/{id}/prices");

    /** Handler patterns FINANCE may POST: a lookup (a read with the phone in the body) and its first write. */
    private static final Set<String> FINANCE_WRITES =
            Set.of("/org/events/{id}/tickets/search", "/org/refunds/{id}/done");

    private final AuthService auth;

    OrgContextInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UUID organizerId = parse(request.getHeader(HEADER));
        UUID userId = UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        StaffRole role = auth.activeRole(organizerId, userId).orElseThrow(OrgContextInterceptor::forbidden);
        boolean read = request.getMethod().equals("GET") || request.getMethod().equals("HEAD");
        // The pattern of the handler Spring matched on the decoded path, never the raw URI (/org/%70os/... is
        // /org/pos/...). Empty or a catch-all pattern when no controller matched: no role-specific access, it ends in a 404.
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String pattern = matched == null ? "" : matched.toString();
        boolean pos = pattern.startsWith("/org/pos/");
        boolean allowed = switch (role) {
            case ADMIN, OPS -> true;
            case FINANCE -> (read && !pos) || FINANCE_WRITES.contains(pattern);
            case CASHIER -> pos || (read && CASHIER_READS.contains(pattern));
            case GATE, SUPERVISOR -> false;
        };
        if (!allowed) {
            throw forbidden();
        }
        request.setAttribute(OrgContext.ATTRIBUTE, new OrgContext(organizerId, userId, role));
        return true;
    }

    private static UUID parse(String header) {
        try {
            if (header != null && header.length() == 36) {
                return UUID.fromString(header);
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to 400
        }
        throw new ApiException("BAD_REQUEST", HttpStatus.BAD_REQUEST, HEADER + " header missing or not a UUID");
    }

    private static ApiException forbidden() {
        return new ApiException("FORBIDDEN", HttpStatus.FORBIDDEN, "Forbidden");
    }
}
