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
}
