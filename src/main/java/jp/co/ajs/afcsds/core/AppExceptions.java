package jp.co.ajs.afcsds.core;

import java.util.Map;

/** Concrete {@link AppException} subclasses (HTTP status / error-type mapping). */
public final class AppExceptions {

    private AppExceptions() {}

    public static class UnsupportedFileTypeException extends AppException {
        public UnsupportedFileTypeException(String message) {
            super(400, "unsupported_file_type", message);
        }

        public UnsupportedFileTypeException(String message, Map<String, Object> details) {
            super(400, "unsupported_file_type", message, details);
        }
    }

    public static class FileTooLargeException extends AppException {
        public FileTooLargeException(String message, Map<String, Object> details) {
            super(400, "file_too_large", message, details);
        }
    }

    public static class TooManyPagesException extends AppException {
        public TooManyPagesException(String message, Map<String, Object> details) {
            super(400, "too_many_pages", message, details);
        }
    }

    public static class EmptyFileException extends AppException {
        public EmptyFileException(String message) {
            super(400, "empty_file", message);
        }
    }

    /** The {@code pages} form field is malformed, out of range, or not applicable. */
    public static class InvalidPageRangeException extends AppException {
        public InvalidPageRangeException(String message, Map<String, Object> details) {
            super(400, "invalid_page_range", message, details);
        }
    }

    public static class UnauthorizedException extends AppException {
        public UnauthorizedException(String message) {
            super(401, "unauthorized", message);
        }
    }

    /**
     * All extraction slots are occupied (MAX_CONCURRENT_EXTRACTIONS); the
     * caller should retry after a short delay. The {@code retry_after_seconds}
     * detail is surfaced as a {@code Retry-After} response header.
     */
    public static class ServerBusyException extends AppException {
        public static final int RETRY_AFTER_SECONDS = 30;

        public ServerBusyException(String message) {
            super(503, "server_busy", message, Map.of("retry_after_seconds", RETRY_AFTER_SECONDS));
        }
    }

    /** Claude declined to process the document for safety reasons. */
    public static class ClaudeRefusalException extends AppException {
        public ClaudeRefusalException(String message, Map<String, Object> details) {
            super(422, "extraction_refused", message, details);
        }
    }

    /** Claude's response hit max_tokens before producing valid JSON. */
    public static class ClaudeTruncatedException extends AppException {
        public ClaudeTruncatedException(String message) {
            super(502, "extraction_truncated", message);
        }
    }

    /** The Anthropic API returned an error we could not recover from. */
    public static class ClaudeUpstreamException extends AppException {
        public ClaudeUpstreamException(int statusCode, String message) {
            super(statusCode, "upstream_error", message);
        }

        public ClaudeUpstreamException(int statusCode, String message, Map<String, Object> details) {
            super(statusCode, "upstream_error", message, details);
        }
    }

    /** Claude's response did not validate against the SDS JSON schema. */
    public static class ClaudeResponseInvalidException extends AppException {
        public ClaudeResponseInvalidException(String message) {
            super(502, "extraction_invalid_response", message);
        }
    }

    /** Anthropic rejected the document itself as unprocessable (caller's fault, not ours). */
    public static class ClaudeInvalidDocumentException extends AppException {
        public ClaudeInvalidDocumentException(String message) {
            super(400, "invalid_document", message);
        }
    }
}
