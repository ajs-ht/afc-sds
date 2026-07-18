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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jp.co.ajs.afcsds.config.AppSettings;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeInvalidDocumentException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeRefusalException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeResponseInvalidException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeTruncatedException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeUpstreamException;
import jp.co.ajs.afcsds.core.AppExceptions.ServerBusyException;
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
 * {@code grammarTooLarge} flag (process-local — the service is a singleton)
 * so later requests skip straight to the fallback. A response that fails schema validation (and
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
    // round-trip every time. The service is a singleton, so this is
    // process-local in practice; it resets on restart (intentional — the
    // limit may have been raised, or the schema shrunk, since the process
    // last ran).
    private final AtomicBoolean grammarTooLarge = new AtomicBoolean(false);

    private final ClaudeGateway gateway;
    private final AppSettings settings;

    // Backpressure: each extraction holds the full file in memory (plus its
    // base64 form) across a minutes-long streaming Claude call, so unbounded
    // concurrency risks memory exhaustion and rate-limit storms. Beyond this
    // many in-flight extractions, requests are rejected immediately with
    // server_busy (503 + Retry-After) instead of queueing.
    private final Semaphore extractionSlots;

    // Daily token totals for the DAILY_TOKEN_BUDGET cost warning.
    private final DailyTokenBudget dailyTokenBudget;

    public ExtractionService(ClaudeGateway gateway, AppSettings settings) {
        this.gateway = gateway;
        this.settings = settings;
        this.extractionSlots = new Semaphore(settings.maxConcurrentExtractions());
        this.dailyTokenBudget =
                new DailyTokenBudget(settings.dailyTokenBudget(), java.time.Clock.systemUTC());
    }

    /**
     * Send an SDS file to Claude and return the validated structured JSON.
     *
     * <p>A response that fails schema validation (without having been
     * truncated by max_tokens) is retried once before surfacing
     * {@code extraction_invalid_response} — on the prompt-embedded-schema
     * path nothing constrains decoding, so a one-off malformed response is
     * recoverable. The retry feeds the failed response and its validation
     * errors back to the model as conversation turns (see
     * {@link ClaudeGateway#requestCorrection}) so it can fix its own output
     * instead of re-rolling blind; a response with no usable text falls back
     * to a plain re-extraction. The returned usage covers all API calls made
     * for the request.
     */
    public SdsExtractionResponse extractSds(byte[] content, String contentType, String requestId) {
        if (!extractionSlots.tryAcquire()) {
            throw new ServerBusyException(
                    "All %d extraction slots are busy; retry after a short delay."
                            .formatted(settings.maxConcurrentExtractions()));
        }
        try {
            return doExtract(content, contentType, requestId);
        } finally {
            extractionSlots.release();
        }
    }

    private SdsExtractionResponse doExtract(byte[] content, String contentType, String requestId) {
        boolean structured = settings.useStructuredOutputs() && !grammarTooLarge.get();

        RequestResult result =
                performRequest(
                        s -> gateway.requestExtraction(content, contentType, s),
                        structured,
                        requestId);
        ClaudeMessage message = result.message();
        structured = result.structured();

        List<ClaudeMessage> apiMessages = new ArrayList<>();
        apiMessages.add(message);

        ParseOutcome outcome = tryParse(message, false);
        SdsDocument parsed = outcome.document();
        boolean retried = parsed == null;

        if (parsed == null) {
            logger.warn(
                    "response failed SDS schema validation; retrying once with the errors fed "
                            + "back (request_id={}): {}",
                    requestId,
                    clip(outcome.error(), 500));
            result = performRequest(correctionCall(content, contentType, message, outcome), structured, requestId);
            message = result.message();
            structured = result.structured();
            apiMessages.add(message);
            parsed = tryParse(message, true).document();
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

        ExtractionUsage usage = sumUsage(apiMessages);
        if (dailyTokenBudget.record(usage.inputTokens() + usage.outputTokens())) {
            logger.warn(
                    "daily token usage {} exceeded DAILY_TOKEN_BUDGET={} (UTC day); requests "
                            + "continue to be served — check for runaway callers (request_id={})",
                    dailyTokenBudget.usedToday(),
                    settings.dailyTokenBudget(),
                    requestId);
        }

        return new SdsExtractionResponse(parsed, warnings, message.model(), usage);
    }

    private record RequestResult(ClaudeMessage message, boolean structured) {}

    /** One gateway invocation, parameterized only by the structured flag. */
    @FunctionalInterface
    private interface GatewayCall {
        ClaudeMessage call(boolean structured);
    }

    /**
     * The retry call after a validation failure: feed the failed response and
     * its errors back for correction, or — when the response had no usable
     * text to correct — fall back to a plain re-extraction.
     */
    private GatewayCall correctionCall(
            byte[] content, String contentType, ClaudeMessage failed, ParseOutcome outcome) {
        String previousText = failed.text();
        if (previousText == null || previousText.isBlank()) {
            return s -> gateway.requestExtraction(content, contentType, s);
        }
        String errors = clip(outcome.error(), MAX_FEEDBACK_ERROR_CHARS);
        return s -> gateway.requestCorrection(content, contentType, s, previousText, errors);
    }

    /**
     * One Claude call: grammar-size fallback, exception mapping, usage log.
     *
     * <p>The returned {@code structured} flips to false when the call fell
     * back to the prompt-embedded schema mid-flight. The fallback call is
     * mapped through the same {@link #mapApiException} logic as the initial
     * call, so a document rejection surfacing on the fallback attempt still
     * becomes {@code invalid_document} rather than a generic upstream error.
     */
    private RequestResult performRequest(GatewayCall call, boolean structured, String requestId) {
        ClaudeMessage message;
        try {
            message = call.call(structured);
        } catch (ClaudeApiException exc) {
            if (!(structured && isGrammarSizeError(exc))) {
                throw mapApiException(exc);
            }
            grammarTooLarge.set(true);
            structured = false;
            logger.warn(
                    "structured-outputs grammar exceeded the API size limit; falling back to "
                            + "the prompt-embedded schema for this and subsequent requests "
                            + "(request_id={}): {}",
                    requestId,
                    exc.getMessage());
            try {
                message = call.call(false);
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
            case RATE_LIMIT ->
                    // retry_after_seconds surfaces as a Retry-After header (see
                    // GlobalExceptionHandler) so callers back off instead of
                    // hammering an already rate-limited upstream.
                    new ClaudeUpstreamException(
                            503,
                            "Rate limited by the Anthropic API.",
                            Map.of("retry_after_seconds", 30));
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

    /** Either a validated document, or the validation error that stopped it. */
    private record ParseOutcome(SdsDocument document, String error) {}

    // Cap on the validation-error text fed back to the model on retry: enough
    // to name every broken field without ballooning the request.
    private static final int MAX_FEEDBACK_ERROR_CHARS = 4000;

    /**
     * Validate a message's text as an SdsDocument.
     *
     * <p>A validation failure on a non-truncated response returns the error
     * text so the caller can retry once with it fed back to the model; with
     * {@code finalAttempt=true} it throws instead. Truncation always throws —
     * retrying the same document would just truncate again.
     */
    private static ParseOutcome tryParse(ClaudeMessage message, boolean finalAttempt) {
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
            return new ParseOutcome(
                    SdsSchema.STRICT_MAPPER.treeToValue(node, SdsDocument.class), null);
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
            return new ParseOutcome(null, String.valueOf(exc.getMessage()));
        }
    }

    private static String clip(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
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
