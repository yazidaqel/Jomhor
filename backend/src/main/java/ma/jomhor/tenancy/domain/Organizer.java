package ma.jomhor.tenancy.domain;

import java.util.UUID;

/**
 * A listed organizer as fans see it (docs/04-api.md §Club directory): nothing else of {@code settings} than the crest,
 * and {@code crestUrl} already through {@link ma.jomhor.common.Crests}. {@code kind} is the organizer_kind enum name.
 */
public record Organizer(UUID id, String slug, String name, String kind, String crestUrl) {}
