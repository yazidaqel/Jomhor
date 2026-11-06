package ma.jomhor.tenancy.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ma.jomhor.common.HolderNames;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.tenancy.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fan accounts for other modules (a ticket holder by phone) and the fan's own profile (GET/PATCH /fan/me). */
@Service
public class UserService {

    private static final Set<String> LOCALES = Set.of("fr", "ar", "en");

    private final Accounts accounts;

    public UserService(Accounts accounts) {
        this.accounts = accounts;
    }

    /** The account of this E.164 phone, created if new (in the caller's transaction, so it rolls back with it). */
    @Transactional
    public UUID holder(String phone) {
        return accounts.upsertByPhone(phone).id();
    }

    public User get(UUID userId) {
        return accounts.findUser(userId)
                .orElseThrow(() -> new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Unknown user"));
    }

    /** null = unchanged; "" clears display_name (04 conventions); else display_name per §Holder names. */
    @Transactional
    public User update(UUID userId, String displayName, String locale) {
        String name = displayName == null || displayName.isEmpty() ? displayName
                : HolderNames.normalize(displayName).orElseThrow(() -> invalid("display_name",
                        "2 to 60 Latin letters, spaces, apostrophes, dots or hyphens"));
        if (locale != null && !LOCALES.contains(locale)) {
            throw invalid("locale", "one of fr, ar, en");
        }
        return accounts.updateProfile(userId, name, locale)
                .orElseThrow(() -> new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Unknown user"));
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of(field, message)));
    }
}
