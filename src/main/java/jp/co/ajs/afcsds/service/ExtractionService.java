package jp.co.ajs.afcsds.service;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jp.co.ajs.afcsds.config.AppSettings;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeInvalidDocumentException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeRefusalException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeResponseInvalidException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeTruncatedException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeUpstreamException;
import jp.co.ajs.afcsds.core.AppLogs;
import jp.co.ajs.afcsds.schema.Responses.ExtractionUsage;
import jp.co.ajs.afcsds.schema.Responses.SdsExtractionResponse;
import jp.co.ajs.afcsds.schema.SdsDocument;
import jp.co.ajs.afcsds.schema.SdsSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Send an SDS file to Claude and return a schema-validated result.
 *
 * <p>Output enforcement is two-tier: structured outputs
 * ({@code output_config.format}) constrain decoding at the API level when
 * enabled and the compiled grammar fits within the API's size limit;
 * otherwise (or when disabled) the schema is embedded in the system prompt
 * instead, and the response is strictly validated against the JSON schema
 * either way. A structured-outputs request that first hits the grammar-size
 * limit falls back to the prompt-embedded schema automatically and flips the
 * process-local {@code GRAMMAR_TOO_LARGE} flag so later requests skip
 * straight to the fallback. A response that fails schema validation (and
 * wasn't truncated by max_tokens) is retried once before giving up. See
 * {@link Prompts} for why structured outputs currently can't host the full
 * SDS schema.
 */
@Service
public class ExtractionService {

    public static final String STRUCTURED_OUTPUTS_UNAVAILABLE_WARNING =
            "structured_outputs_unavailable";
    public static final String RETRIED_INVALID_RESPONSE_WARNING = "retried_invalid_response";
    public static final String ADDITIONAL_SDS_DOCUMENTS_WARNING =
            "additional_sds_documents_detected";
    public static final String OUTPUT_TRUNCATED_WARNING = "output_truncated_max_tokens";

    private static final Logger logger = LoggerFactory.getLogger("afc_sds.extraction");

    private static final Pattern CODE_FENCE = Pattern.compile("^```(?:json)?\\s*\\n?|\\n?```\\s*$");

    // Set to true the first time the API rejects our schema with a
    // compiled-grammar size error, so subsequent requests go straight to the
    // prompt-embedded-schema fallback instead of paying a doomed 400
    // round-trip every time. Process-local; resets on restart (intentional —
    // the limit may have been raised, or the schema shrunk, since the process
    // last ran).
    private static final AtomicBoolean GRAMMAR_TOO_LARGE = new AtomicBoolean(false);

    /** Test hook: the grammar fallback flag is process-local state. */
    static void resetGrammarTooLarge() {
        GRAMMAR_TOO_LARGE.set(false);
    }

    private final ClaudeGateway gateway;
    private final AppSettings settings;

    public ExtractionService(ClaudeGateway gateway, AppSettings settings) {
        this.gateway = gateway;
        this.settings = settings;
    }

    /**
     * Send an SDS file to Claude and return the validated structured JSON.
     *
     * <p>A response that fails schema validation (without having been
     * truncated by max_tokens) is retried once before surfacing
     * {@code extraction_invalid_response} — on the prompt-embedded-schema
     * path nothing constrains decoding, so a one-off malformed response is
     * recoverable. The returned usage covers all API calls made for the
     * request.
     */
    public SdsExtractionResponse extractSds(byte[] content, String contentType, String requestId) {
        boolean structured = settings.useStructuredOutputs() && !GRAMMAR_TOO_LARGE.get();

        RequestResult result = requestExtraction(content, contentType, structured, requestId);
        ClaudeMessage message = result.message();
        structured = result.structured();

        List<ClaudeMessage> apiMessages = new ArrayList<>();
        apiMessages.add(message);

        SdsDocument parsed = parseDocument(message, false);
        boolean retried = parsed == null;

        if (parsed == null) {
            logger.warn(
                    "response failed SDS schema validation; retrying once (request_id={})", requestId);
            result = requestExtraction(content, contentType, structured, requestId);
            message = result.message();
            structured = result.structured();
            apiMessages.add(message);
            parsed = parseDocument(message, true);
        }

        List<String> warnings = new ArrayList<>();
        if ("max_tokens".equals(message.stopReason())) {
            warnings.add(OUTPUT_TRUNCATED_WARNING);
        }
        if (retried) {
            warnings.add(RETRIED_INVALID_RESPONSE_WARNING);
        }
        if (settings.useStructuredOutputs() && !structured) {
            warnings.add(STRUCTURED_OUTPUTS_UNAVAILABLE_WARNING);
        }
        if (!parsed.additionalDocuments.isEmpty()) {
            // Multi-SDS file: only the first SDS was extracted. Callers
            // re-fetch the rest via the `pages` form field using the reported
            // page ranges.
            warnings.add(ADDITIONAL_SDS_DOCUMENTS_WARNING);
        }
        warnings.addAll(PostValidation.collectDomainWarnings(parsed));

        return new SdsExtractionResponse(parsed, warnings, message.model(), sumUsage(apiMessages));
    }

    private record RequestResult(ClaudeMessage message, boolean structured) {}

    /**
     * One Claude call: grammar-size fallback, exception mapping, usage log.
     *
     * <p>The returned {@code structured} flips to false when the call fell
     * back to the prompt-embedded schema mid-flight. The fallback call is
     * mapped through the same {@link #mapApiException} logic as the initial
     * call, so a document rejection surfacing on the fallback attempt still
     * becomes {@code invalid_document} rather than a generic upstream error.
     */
    private RequestResult requestExtraction(
            byte[] content, String contentType, boolean structured, String requestId) {
        ClaudeMessage message;
        try {
            message = gateway.requestExtraction(content, contentType, structured);
        } catch (ClaudeApiException exc) {
            if (!(structured && isGrammarSizeError(exc))) {
                throw mapApiException(exc);
            }
            GRAMMAR_TOO_LARGE.set(true);
            structured = false;
            logger.warn(
                    "structured-outputs grammar exceeded the API size limit; falling back to "
                            + "the prompt-embedded schema for this and subsequent requests "
                            + "(request_id={}): {}",
                    requestId,
                    exc.getMessage());
            try {
                message = gateway.requestExtraction(content, contentType, false);
            } catch (ClaudeApiException retryExc) {
                throw mapApiException(retryExc);
            }
        }

        ClaudeMessage.Usage usage = message.usage();
        AppLogs.logUsage(
                requestId,
                message.model(),
                message.stopReason(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheCreationInputTokens(),
                usage.cacheReadInputTokens());

        if ("refusal".equals(message.stopReason())) {
            Map<String, Object> details = new HashMap<>();
            details.put("category", message.refusalCategory());
            throw new ClaudeRefusalException("Claude declined to process this document.", details);
        }

        return new RequestResult(message, structured);
    }

    private static boolean isGrammarSizeError(ClaudeApiException exc) {
        if (exc.kind() != ClaudeApiException.Kind.BAD_REQUEST) {
            return false;
        }
        String message = exc.getMessage() == null ? "" : exc.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("grammar")
                && (message.contains("too large") || message.contains("too complex"));
    }

    /**
     * Map a gateway failure to the AppException the caller should see: a
     * {@code BAD_REQUEST} means Anthropic rejected the document/request
     * itself (the caller's fault), anything else is an upstream failure.
     * Shared by both the initial call and the grammar-size fallback call so
     * either one is classified the same way.
     */
    private static RuntimeException mapApiException(ClaudeApiException exc) {
        if (exc.kind() == ClaudeApiException.Kind.BAD_REQUEST) {
            return new ClaudeInvalidDocumentException(
                    "Anthropic rejected the document as invalid: " + exc.getMessage());
        }
        return switch (exc.kind()) {
            case RATE_LIMIT -> new ClaudeUpstreamException(503, "Rate limited by the Anthropic API.");
            case CONNECTION -> new ClaudeUpstreamException(503, "Could not reach the Anthropic API.");
            case SERVER_ERROR ->
                    new ClaudeUpstreamException(503, "The Anthropic API returned a server error.");
            case CONFIG ->
                    new ClaudeUpstreamException(
                            500,
                            "Anthropic API request was rejected due to a server-side configuration issue.");
            default ->
                    new ClaudeUpstreamException(500, "Unexpected Anthropic API error: " + exc.getMessage());
        };
    }

    /**
     * Validate a message's text as an SdsDocument.
     *
     * <p>A validation failure on a non-truncated response returns {@code null}
     * so the caller can retry once; with {@code finalAttempt=true} it throws
     * instead. Truncation always throws — retrying the same document would
     * just truncate again.
     */
    private static SdsDocument parseDocument(ClaudeMessage message, boolean finalAttempt) {
        String text = stripCodeFence(extractTextBlock(message));
        try {
            JsonNode node = SdsSchema.STRICT_MAPPER.readTree(text);
            if (node == null || node.isMissingNode()) {
                throw new IllegalArgumentException("response text is not JSON");
            }
            Set<ValidationMessage> errors = SdsSchema.VALIDATOR.validate(node);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(
                        errors.stream().map(ValidationMessage::toString).collect(Collectors.joining("; ")));
            }
            return SdsSchema.STRICT_MAPPER.treeToValue(node, SdsDocument.class);
        } catch (JacksonException | IllegalArgumentException exc) {
            if ("max_tokens".equals(message.stopReason())) {
                throw new ClaudeTruncatedException(
                        "Claude's response was truncated (max_tokens) before completing valid JSON.");
            }
            if (finalAttempt) {
                throw new ClaudeResponseInvalidException(
                        "Claude's response did not match the expected SDS schema "
                                + "(even after one retry): "
                                + exc.getMessage());
            }
            return null;
        }
    }

    private static ExtractionUsage sumUsage(List<ClaudeMessage> messages) {
        return new ExtractionUsage(
                messages.stream().mapToLong(m -> m.usage().inputTokens()).sum(),
                messages.stream().mapToLong(m -> m.usage().outputTokens()).sum(),
                messages.stream().mapToLong(m -> m.usage().cacheCreationInputTokens()).sum(),
                messages.stream().mapToLong(m -> m.usage().cacheReadInputTokens()).sum());
    }

    private static String extractTextBlock(ClaudeMessage message) {
        if (message.text() == null) {
            throw new ClaudeResponseInvalidException(
                    "Claude's response contained no text content block.");
        }
        return message.text();
    }

    /**
     * Strip a wrapping ```json ... ``` fence, if Claude added one.
     *
     * <p>On the prompt-embedded-schema fallback path (no constrained
     * decoding), Claude occasionally wraps its JSON in a markdown code fence
     * despite being told not to; this undoes that so JSON parsing sees raw
     * JSON. On the structured-outputs path the fence can't occur, and this is
     * a no-op.
     */
    private static String stripCodeFence(String text) {
        text = text.strip();
        if (text.startsWith("```")) {
            text = CODE_FENCE.matcher(text).replaceAll("").strip();
        }
        return text;
    }
}
