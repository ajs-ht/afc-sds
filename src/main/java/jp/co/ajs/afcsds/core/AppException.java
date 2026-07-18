package jp.co.ajs.afcsds.core;

import java.util.Map;

/**
 * Base class for all application errors that map to an HTTP response.
 *
 * <p>Each exception carries the HTTP status code and a machine-readable error
 * {@code type} string so the global exception handler (see
 * {@code web.GlobalExceptionHandler}) can map it to a consistent JSON error
 * body without per-route try/catch blocks.
 */
public class AppException extends RuntimeException {

    private final int statusCode;
    private final String errorType;
    private final Map<String, Object> details;

    public AppException(int statusCode, String errorType, String message) {
        this(statusCode, errorType, message, Map.of());
    }

    public AppException(int statusCode, String errorType, String message, Map<String, Object> details) {
        super(message);
        this.statusCode = statusCode;
        this.errorType = errorType;
        this.details = details;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorType() {
        return errorType;
    }

    public Map<String, Object> details() {
        return details;
    }
}
