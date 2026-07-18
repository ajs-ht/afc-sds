package jp.co.ajs.afcsds.service;

/**
 * Thin boundary in front of the Anthropic SDK.
 *
 * <p>{@link ExtractionService} depends on this interface (easy to fake in
 * tests); {@link AnthropicClaudeGateway} is the production implementation.
 * SDK exceptions are translated into {@link ClaudeApiException} so the
 * retry/fallback logic in the service can branch without depending on SDK
 * exception classes.
 */
public interface ClaudeGateway {

    /**
     * One Claude call: send the document and return the final message.
     *
     * @param structured request structured outputs ({@code output_config.format})
     *     instead of the prompt-embedded schema
     * @throws ClaudeApiException when the Anthropic API call fails
     */
    ClaudeMessage requestExtraction(byte[] content, String contentType, boolean structured);

    /**
     * Retry after a schema-validation failure: the same document plus the
     * failed response (as an assistant turn) and its validation errors (as a
     * follow-up user turn), so the model corrects its own output instead of
     * re-rolling blind. The system prompt is unchanged, so prompt caching
     * still applies.
     *
     * <p>The default implementation ignores the feedback and re-extracts from
     * scratch, so simple fakes stay a single lambda; the production gateway
     * overrides it with the real conversational retry.
     *
     * @param previousResponseText the model's schema-invalid response, fed
     *     back verbatim; must be non-blank (callers fall back to
     *     {@link #requestExtraction} otherwise)
     * @param validationErrors human-readable description of what failed
     * @throws ClaudeApiException when the Anthropic API call fails
     */
    default ClaudeMessage requestCorrection(
            byte[] content,
            String contentType,
            boolean structured,
            String previousResponseText,
            String validationErrors) {
        return requestExtraction(content, contentType, structured);
    }
}
