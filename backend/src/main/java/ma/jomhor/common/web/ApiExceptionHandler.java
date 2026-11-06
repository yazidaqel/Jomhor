package ma.jomhor.common.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // Field paths are reported as the client sent them (JSON is snake_case, see application.yml).
    private static final PropertyNamingStrategies.NamingBase SNAKE =
            (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE;

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> fields.putIfAbsent(SNAKE.translate(f.getField()), f.getDefaultMessage()));
        return validationFailed(fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return validationFailed(Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
        return api(ApiException.notFound("Not found"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest(HttpStatus.BAD_REQUEST, HttpHeaders.EMPTY);
    }

    /** Spring's own 4xx (405, 415, missing header, ...) keep their status; anything else is a 500. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e) {
        if (e instanceof ErrorResponse r && r.getStatusCode().is4xxClientError()) {
            return badRequest(r.getStatusCode(), r.getHeaders());
        }
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(new ApiError("INTERNAL_ERROR", "Internal error", Map.of()));
    }

    // Fixed message (the reason phrase): framework messages can echo request input.
    private static ResponseEntity<ApiError> badRequest(HttpStatusCode status, HttpHeaders headers) {
        var reason = HttpStatus.resolve(status.value());
        return ResponseEntity.status(status).headers(headers)
                .body(new ApiError("BAD_REQUEST", reason == null ? "Bad request" : reason.getReasonPhrase(), Map.of()));
    }

    private static ResponseEntity<ApiError> validationFailed(Map<String, Object> fields) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("VALIDATION_FAILED", "Invalid request body", Map.of("fields", fields)));
    }
}
