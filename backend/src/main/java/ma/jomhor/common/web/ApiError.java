package ma.jomhor.common.web;

import java.util.Map;

/** Error body of every API response, docs/04-api.md header. */
public record ApiError(String code, String message, Map<String, Object> details) {}
