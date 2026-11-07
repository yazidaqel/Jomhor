package ma.jomhor.tenancy.adapters.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.tenancy.application.AuthService;
import ma.jomhor.tenancy.domain.OrgContext;
import ma.jomhor.tenancy.domain.StaffRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Every /org/** call except /org/auth/** and /org/me: X-Organizer-Id → active staff row of the
 * token subject → {@link OrgContext} request attribute. Lot 0 roles: ADMIN, OPS read and write;
 * FINANCE read only; GATE, SUPERVISOR 403. Lot 3: /org/pos/** is ADMIN, OPS, CASHIER; CASHIER may call nothing else
 * but GET /org/events, /org/events/{id} and /org/events/{id}/prices.
 */
class OrgContextInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Organizer-Id";
    private static final Pattern CASHIER_READS = Pattern.compile("/org/events(/[^/]+(/prices)?)?");

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
        // Without the context path (/v1); the firewall has already refused "..", "//", ";" and encoded slashes.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean pos = path.startsWith("/org/pos/");
        boolean allowed = switch (role) {
            case ADMIN, OPS -> true;
            case FINANCE -> read && !pos;
            case CASHIER -> pos || (read && CASHIER_READS.matcher(path).matches());
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
