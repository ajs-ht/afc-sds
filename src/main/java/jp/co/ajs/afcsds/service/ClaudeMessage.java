package jp.co.ajs.afcsds.service;

/**
 * Provider-neutral view of a completed Claude message — just the fields the
 * extraction flow needs.
 *
 * @param text the first text content block, or {@code null} if the response
 *     contained no text block
 * @param refusalCategory {@code stop_details.category} when the model refused,
 *     else {@code null}
 */
public record ClaudeMessage(
        String model, String stopReason, String refusalCategory, String text, Usage usage) {

    public record Usage(
            long inputTokens,
            long outputTokens,
            long cacheCreationInputTokens,
            long cacheReadInputTokens) {}
}
