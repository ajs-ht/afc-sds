package jp.co.ajs.afcsds.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import java.util.Base64;
import java.util.List;
import jp.co.ajs.afcsds.config.AppSettings;
import jp.co.ajs.afcsds.schema.SdsSchema;
import org.springframework.stereotype.Component;

/**
 * Production {@link ClaudeGateway} backed by the official Anthropic Java SDK.
 *
 * <p>Always streams the response ({@code createStreaming} + accumulator)
 * rather than a plain create call, so large extractions never hit the SDK's
 * non-streaming timeout guard regardless of document density (the Java SDK
 * extends its timeout for streaming requests).
 *
 * <p>Note: sampling params (temperature/top_p/top_k) are intentionally
 * absent — they are removed on Opus 4.7+ models and the API rejects them
 * with a 400. Extraction consistency is carried by the transcription-style
 * prompt instead. This assumption was only verified against Opus; re-check
 * it if {@code MODEL_ID} points at a different model family.
 */
@Component
public class AnthropicClaudeGateway implements ClaudeGateway {

    private static final String PDF_MIME_TYPE = "application/pdf";

    private final AnthropicClient client;
    private final AppSettings settings;

    @org.springframework.beans.factory.annotation.Autowired
    public AnthropicClaudeGateway(AppSettings settings) {
        // maxRetries is the SDK's built-in retry-with-backoff for transient
        // failures (429/5xx/connection errors); set explicitly (and made
        // configurable via ANTHROPIC_MAX_RETRIES) rather than relying on the
        // SDK default silently.
        this(
                AnthropicOkHttpClient.builder()
                        .apiKey(settings.anthropicApiKey())
                        .maxRetries(settings.anthropicMaxRetries())
                        .build(),
                settings);
    }

    AnthropicClaudeGateway(AnthropicClient client, AppSettings settings) {
        this.client = client;
        this.settings = settings;
    }

    @Override
    public ClaudeMessage requestExtraction(byte[] content, String contentType, boolean structured) {
        return execute(paramsBuilder(content, contentType, structured).build());
    }

    @Override
    public ClaudeMessage requestCorrection(
            byte[] content,
            String contentType,
            boolean structured,
            String previousResponseText,
            String validationErrors) {
        // Same request as the initial extraction (same cached system prompt),
        // continued as a conversation: the failed response comes back as an
        // assistant turn, then a user turn names the validation errors and
        // asks for a full corrected re-output.
        MessageCreateParams params =
                paramsBuilder(content, contentType, structured)
                        .addAssistantMessage(previousResponseText)
                        .addUserMessage(
                                Prompts.CORRECTION_INSTRUCTION_TEMPLATE.formatted(validationErrors))
                        .build();
        return execute(params);
    }

    private ClaudeMessage execute(MessageCreateParams params) {
        Message message;
        try {
            message = streamFinalMessage(params);
        } catch (BadRequestException e) {
            throw new ClaudeApiException(ClaudeApiException.Kind.BAD_REQUEST, e.getMessage(), e);
        } catch (RateLimitException e) {
            throw new ClaudeApiException(ClaudeApiException.Kind.RATE_LIMIT, e.getMessage(), e);
        } catch (InternalServerException e) {
            throw new ClaudeApiException(ClaudeApiException.Kind.SERVER_ERROR, e.getMessage(), e);
        } catch (UnauthorizedException | PermissionDeniedException | NotFoundException e) {
            // Our own misconfiguration (bad key, bad model id) — not the caller's fault.
            throw new ClaudeApiException(ClaudeApiException.Kind.CONFIG, e.getMessage(), e);
        } catch (AnthropicServiceException e) {
            throw new ClaudeApiException(ClaudeApiException.Kind.OTHER, e.getMessage(), e);
        } catch (AnthropicIoException e) {
            throw new ClaudeApiException(ClaudeApiException.Kind.CONNECTION, e.getMessage(), e);
        }
        return toClaudeMessage(message);
    }

    private Message streamFinalMessage(MessageCreateParams params) {
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
            MessageAccumulator accumulator = MessageAccumulator.create();
            stream.stream().forEach(accumulator::accumulate);
            return accumulator.message();
        }
    }

    private MessageCreateParams.Builder paramsBuilder(
            byte[] content, String contentType, boolean structured) {
        String systemPrompt =
                structured ? Prompts.SYSTEM_PROMPT_BASE : Prompts.SYSTEM_PROMPT_WITH_SCHEMA;

        MessageCreateParams.Builder builder =
                MessageCreateParams.builder()
                        .model(settings.modelId())
                        .maxTokens(settings.maxOutputTokens())
                        .systemOfTextBlockParams(
                                List.of(
                                        TextBlockParam.builder()
                                                .text(systemPrompt)
                                                .cacheControl(CacheControlEphemeral.builder().build())
                                                .build()))
                        .addUserMessageOfBlockParams(
                                List.of(
                                        buildContentBlock(content, contentType),
                                        ContentBlockParam.ofText(
                                                TextBlockParam.builder()
                                                        .text(Prompts.USER_INSTRUCTION)
                                                        .build())));

        if (structured) {
            // Constrained decoding: output_config.format with the SDS schema.
            builder.outputConfig(
                    OutputConfig.builder()
                            .format(JsonOutputFormat.builder().schema(SDS_OUTPUT_SCHEMA).build())
                            .build());
        }

        return builder;
    }

    // The SDS schema is immutable, so its SDK representation is converted once
    // instead of per request.
    private static final JsonOutputFormat.Schema SDS_OUTPUT_SCHEMA = sdsOutputSchema();

    private static JsonOutputFormat.Schema sdsOutputSchema() {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> schemaMap =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .convertValue(SdsSchema.SCHEMA_NODE, java.util.Map.class);
        schemaMap.forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
        return schema.build();
    }

    private ContentBlockParam buildContentBlock(byte[] content, String contentType) {
        String data = Base64.getEncoder().encodeToString(content);
        if (PDF_MIME_TYPE.equals(contentType)) {
            return ContentBlockParam.ofDocument(
                    DocumentBlockParam.builder()
                            .source(Base64PdfSource.builder().data(data).build())
                            .build());
        }
        return ContentBlockParam.ofImage(
                ImageBlockParam.builder()
                        .source(
                                Base64ImageSource.builder()
                                        .data(data)
                                        .mediaType(imageMediaType(contentType))
                                        .build())
                        .build());
    }

    private static Base64ImageSource.MediaType imageMediaType(String contentType) {
        return switch (contentType) {
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/jpeg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> throw new IllegalArgumentException("Unsupported image type: " + contentType);
        };
    }

    private static ClaudeMessage toClaudeMessage(Message message) {
        String text =
                message.content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(TextBlock::text)
                        .findFirst()
                        .orElse(null);

        String stopReason = message.stopReason().map(StopReason::asString).orElse(null);

        var usage = message.usage();
        ClaudeMessage.Usage claudeUsage =
                new ClaudeMessage.Usage(
                        usage.inputTokens(),
                        usage.outputTokens(),
                        usage.cacheCreationInputTokens().orElse(0L),
                        usage.cacheReadInputTokens().orElse(0L));

        // stop_details is only populated when the model refused.
        String refusalCategory =
                message.stopDetails()
                        .flatMap(RefusalStopDetails::category)
                        .map(RefusalStopDetails.Category::asString)
                        .orElse(null);

        return new ClaudeMessage(
                message.model().asString(), stopReason, refusalCategory, text, claudeUsage);
    }
}
