package ma.jomhor.common.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Thrown by any module to produce a docs/04-api.md error: {@code { "code", "message", "details" }}. */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public ApiException(String code, HttpStatus status, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details == null ? Map.of() : details;
    }

    public ApiException(String code, HttpStatus status, String message) {
        this(code, status, message, Map.of());
    }

    public static ApiException notFound(String message) {
        return new ApiException("NOT_FOUND", HttpStatus.NOT_FOUND, message);
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }
}
