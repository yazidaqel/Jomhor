package ma.jomhor.common;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * docs/04-api.md §Club directory {@code crest_url}: the one rule tenancy and catalog both apply. A crest URL reaches
 * fans only when it starts with {@code jomhor.crests.base-url} (an https URL ending in "/", checked at startup, so the
 * prefix pins scheme, host and path: {@code https://assets.jomhor.ma.evil/…}, {@code http://…} and user-info tricks
 * never match) and parses as a URI; else null.
 */
@Component
public class Crests {

    private final String base;

    Crests(@Value("${jomhor.crests.base-url}") String base) {
        URI uri = URI.create(base);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !base.endsWith("/")) {
            throw new IllegalStateException("jomhor.crests.base-url must be an https URL ending in /");
        }
        this.base = base;
    }

    /** {@code settings.crest_url} as fans may see it: itself under the base URL, else null. */
    public String url(String crestUrl) {
        if (crestUrl == null || !crestUrl.startsWith(base)) {
            return null;
        }
        try {
            URI.create(crestUrl); // no spaces, backslashes or control characters
            return crestUrl;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
