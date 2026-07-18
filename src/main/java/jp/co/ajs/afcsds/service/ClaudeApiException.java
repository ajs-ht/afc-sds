package jp.co.ajs.afcsds.service;

/**
 * Anthropic API failure, classified into the categories the extraction flow
 * distinguishes (mirrors the SDK exception groups the Python version caught).
 */
public class ClaudeApiException extends RuntimeException {

    public enum Kind {
        /** 400 — either an unprocessable document or a grammar-size rejection. */
        BAD_REQUEST,
        /** 429 rate limit. */
        RATE_LIMIT,
        /** Network / connection / timeout failure before or during the response. */
        CONNECTION,
        /** 5xx from the Anthropic API. */
        SERVER_ERROR,
        /** 401/403/404 — our own misconfiguration (bad key, bad model id). */
        CONFIG,
        /** Any other API error. */
        OTHER
    }

    private final Kind kind;

    public ClaudeApiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
