package ma.jomhor.tenancy.domain;

import java.util.UUID;

public record User(UUID id, String phone, String displayName, String locale) {}
