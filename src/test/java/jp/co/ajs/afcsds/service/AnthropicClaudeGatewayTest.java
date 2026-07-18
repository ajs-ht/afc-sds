package jp.co.ajs.afcsds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStopEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import jp.co.ajs.afcsds.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the SDK adapter with the {@link AnthropicClient} mocked out:
 * request construction (prompt variant, cache_control, content blocks,
 * structured outputs), response mapping to {@link ClaudeMessage}, and SDK
 * exception translation to {@link ClaudeApiException} kinds. Only the live
 * gated {@code LiveExtractionTest} exercises the real API.
 */
class AnthropicClaudeGatewayTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 test".getBytes();

    private final AnthropicClient client = mock(AnthropicClient.class);
    private final MessageService messageService = mock(MessageService.class);
    private AnthropicClaudeGateway gateway;

    @BeforeEach
    void setUp() {
        when(client.messages()).thenReturn(messageService);
        gateway = new AnthropicClaudeGateway(client, TestFixtures.settings());
    }

    // --- fixtures -----------------------------------------------------------

    private static Message sdkMessage(
            String text, StopReason stopReason, RefusalStopDetails stopDetails) {
        Usage usage =
                Usage.builder()
                        .inputTokens(1000L)
                        .outputTokens(500L)
                        .cacheCreationInputTokens(200L)
                        // Absent in the API response; the adapter defaults it to 0.
                        .cacheReadInputTokens(Optional.empty())
                        .cacheCreation(Optional.empty())
                        .inferenceGeo(Optional.empty())
                        .outputTokensDetails(Optional.empty())
                        .serverToolUse(Optional.empty())
                        .serviceTier(Optional.empty())
                        .build();
        return Message.builder()
                .id("msg_test")
                .model("claude-opus-4-8")
                .content(
                        text == null
                                ? List.of()
                                : List.of(
                                        ContentBlock.ofText(
                                                TextBlock.builder()
                                                        .text(text)
                                                        .citations(Optional.empty())
                                                        .build())))
                .stopReason(Optional.ofNullable(stopReason))
                .stopSequence(Optional.empty())
                .stopDetails(Optional.ofNullable(stopDetails))
                .usage(usage)
                .build();
    }

    /**
     * A streamed response delivering {@code message}: message_start, one
     * content_block_start per text block (the accumulator rebuilds content
     * from block events, not from the start message), message_stop.
     */
    private static StreamResponse<RawMessageStreamEvent> streamOf(Message message) {
        List<RawMessageStreamEvent> events = new java.util.ArrayList<>();
        events.add(
                RawMessageStreamEvent.ofMessageStart(
                        RawMessageStartEvent.builder().message(message).build()));
        for (int i = 0; i < message.content().size(); i++) {
            events.add(
                    RawMessageStreamEvent.ofContentBlockStart(
                            RawContentBlockStartEvent.builder()
                                    .index(i)
                                    .contentBlock(message.content().get(i).asText())
                                    .build()));
        }
        events.add(RawMessageStreamEvent.ofMessageStop(RawMessageStopEvent.builder().build()));
        return new StreamResponse<>() {
            @Override
            public Stream<RawMessageStreamEvent> stream() {
                return events.stream();
            }

            @Override
            public void close() {}
        };
    }

    private void stubResponse(Message message) {
        when(messageService.createStreaming(any(MessageCreateParams.class)))
                .thenAnswer(invocation -> streamOf(message));
    }

    private MessageCreateParams capturedParams() {
        ArgumentCaptor<MessageCreateParams> captor =
                ArgumentCaptor.forClass(MessageCreateParams.class);
        org.mockito.Mockito.verify(messageService).createStreaming(captor.capture());
        return captor.getValue();
    }

    // --- request construction ------------------------------------------------

    @Test
    void pdfRequestEmbedsSchemaPromptWithCacheControl() {
        stubResponse(sdkMessage("{}", StopReason.END_TURN, null));

        gateway.requestExtraction(PDF_BYTES, "application/pdf", false);

        MessageCreateParams params = capturedParams();
        assertThat(params.model().asString()).isEqualTo("claude-opus-4-8");
        assertThat(params.maxTokens()).isEqualTo(24000);

        // Non-structured mode: the schema rides in the system prompt, and the
        // block must carry cache_control so prompt caching can hit.
        List<TextBlockParam> system = params.system().orElseThrow().asTextBlockParams();
        assertThat(system).hasSize(1);
        assertThat(system.get(0).text()).isEqualTo(Prompts.SYSTEM_PROMPT_WITH_SCHEMA);
        assertThat(system.get(0).cacheControl()).isPresent();
        assertThat(params.outputConfig()).isEmpty();

        List<ContentBlockParam> blocks =
                params.messages().get(0).content().asBlockParams();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).document().orElseThrow().source().asBase64().data())
                .isEqualTo(Base64.getEncoder().encodeToString(PDF_BYTES));
        assertThat(blocks.get(1).text().orElseThrow().text()).isEqualTo(Prompts.USER_INSTRUCTION);
    }

    @Test
    void structuredRequestUsesBasePromptAndOutputFormat() {
        stubResponse(sdkMessage("{}", StopReason.END_TURN, null));

        gateway.requestExtraction(PDF_BYTES, "application/pdf", true);

        MessageCreateParams params = capturedParams();
        List<TextBlockParam> system = params.system().orElseThrow().asTextBlockParams();
        assertThat(system.get(0).text()).isEqualTo(Prompts.SYSTEM_PROMPT_BASE);
        assertThat(system.get(0).cacheControl()).isPresent();

        // Constrained decoding carries the schema instead of the prompt.
        var format = params.outputConfig().orElseThrow().format().orElseThrow();
        assertThat(format.schema()._additionalProperties()).containsKey("properties");
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "image/jpeg", "image/webp"})
    void imageRequestBuildsBase64ImageBlock(String contentType) {
        stubResponse(sdkMessage("{}", StopReason.END_TURN, null));
        byte[] imageBytes = {1, 2, 3};

        gateway.requestExtraction(imageBytes, contentType, false);

        List<ContentBlockParam> blocks =
                capturedParams().messages().get(0).content().asBlockParams();
        Base64ImageSource source = blocks.get(0).image().orElseThrow().source().asBase64();
        // The declared MIME type must ride along as the block's media_type.
        assertThat(source.mediaType().asString()).isEqualTo(contentType);
        assertThat(source.data()).isEqualTo(Base64.getEncoder().encodeToString(imageBytes));
    }

    @Test
    void unsupportedImageTypeIsRejectedBeforeTheApiCall() {
        // validateUpload() upstream prevents this, but the adapter must not
        // silently send an unlabeled image if that invariant ever breaks.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> gateway.requestExtraction(new byte[] {1}, "image/gif", false));
    }

    // --- response mapping ----------------------------------------------------

    @Test
    void responseFieldsMapToClaudeMessage() {
        stubResponse(sdkMessage("{\"a\":1}", StopReason.END_TURN, null));

        ClaudeMessage result = gateway.requestExtraction(PDF_BYTES, "application/pdf", false);

        assertThat(result.model()).isEqualTo("claude-opus-4-8");
        assertThat(result.stopReason()).isEqualTo("end_turn");
        assertThat(result.text()).isEqualTo("{\"a\":1}");
        assertThat(result.refusalCategory()).isNull();
        assertThat(result.usage().inputTokens()).isEqualTo(1000);
        assertThat(result.usage().outputTokens()).isEqualTo(500);
        assertThat(result.usage().cacheCreationInputTokens()).isEqualTo(200);
        // Absent cache-read count defaults to 0, not an error.
        assertThat(result.usage().cacheReadInputTokens()).isEqualTo(0);
    }

    @Test
    void refusalCategoryIsExtractedFromStopDetails() {
        RefusalStopDetails details =
                RefusalStopDetails.builder()
                        .category(RefusalStopDetails.Category.CYBER)
                        .explanation(Optional.empty())
                        .build();
        stubResponse(sdkMessage(null, StopReason.REFUSAL, details));

        ClaudeMessage result = gateway.requestExtraction(PDF_BYTES, "application/pdf", false);

        assertThat(result.stopReason()).isEqualTo("refusal");
        assertThat(result.refusalCategory()).isEqualTo("cyber");
        assertThat(result.text()).isNull();
    }

    @Test
    void missingOptionalResponseFieldsMapToNulls() {
        stubResponse(sdkMessage(null, null, null));

        ClaudeMessage result = gateway.requestExtraction(PDF_BYTES, "application/pdf", false);

        assertThat(result.stopReason()).isNull();
        assertThat(result.refusalCategory()).isNull();
        assertThat(result.text()).isNull();
    }

    // --- SDK exception translation -------------------------------------------

    static Stream<org.junit.jupiter.params.provider.Arguments> sdkExceptionKinds() {
        Headers headers = Headers.builder().build();
        JsonValue body = JsonValue.from(Map.of("error", "boom"));
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        BadRequestException.builder().headers(headers).body(body).build(),
                        ClaudeApiException.Kind.BAD_REQUEST),
                org.junit.jupiter.params.provider.Arguments.of(
                        RateLimitException.builder().headers(headers).body(body).build(),
                        ClaudeApiException.Kind.RATE_LIMIT),
                org.junit.jupiter.params.provider.Arguments.of(
                        InternalServerException.builder()
                                .statusCode(500)
                                .headers(headers)
                                .body(body)
                                .build(),
                        ClaudeApiException.Kind.SERVER_ERROR),
                org.junit.jupiter.params.provider.Arguments.of(
                        UnauthorizedException.builder().headers(headers).body(body).build(),
                        ClaudeApiException.Kind.CONFIG),
                org.junit.jupiter.params.provider.Arguments.of(
                        PermissionDeniedException.builder().headers(headers).body(body).build(),
                        ClaudeApiException.Kind.CONFIG),
                org.junit.jupiter.params.provider.Arguments.of(
                        NotFoundException.builder().headers(headers).body(body).build(),
                        ClaudeApiException.Kind.CONFIG),
                org.junit.jupiter.params.provider.Arguments.of(
                        UnprocessableEntityException.builder().headers(headers).body(body).build(),
                        ClaudeApiException.Kind.OTHER),
                org.junit.jupiter.params.provider.Arguments.of(
                        new AnthropicIoException("connection reset"),
                        ClaudeApiException.Kind.CONNECTION));
    }

    @ParameterizedTest
    @MethodSource("sdkExceptionKinds")
    void sdkExceptionsTranslateToApiExceptionKinds(
            AnthropicException sdkException, ClaudeApiException.Kind expectedKind) {
        when(messageService.createStreaming(any(MessageCreateParams.class))).thenThrow(sdkException);

        assertThatExceptionOfType(ClaudeApiException.class)
                .isThrownBy(() -> gateway.requestExtraction(PDF_BYTES, "application/pdf", false))
                .satisfies(exc -> assertThat(exc.kind()).isEqualTo(expectedKind))
                .withCause(sdkException);
    }

    // --- Spring wiring --------------------------------------------------------

    @Test
    void autowiredConstructorBuildsARealClient() {
        // The production constructor path: builds the OkHttp-backed client
        // from AppSettings (no network I/O happens at construction time).
        new AnthropicClaudeGateway(TestFixtures.settings());
    }
}
