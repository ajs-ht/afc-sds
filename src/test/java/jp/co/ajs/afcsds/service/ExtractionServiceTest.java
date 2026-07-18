package jp.co.ajs.afcsds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import jp.co.ajs.afcsds.TestFixtures;
import jp.co.ajs.afcsds.config.AppSettings;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeInvalidDocumentException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeRefusalException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeResponseInvalidException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeTruncatedException;
import jp.co.ajs.afcsds.core.AppExceptions.ClaudeUpstreamException;
import jp.co.ajs.afcsds.schema.Responses.SdsExtractionResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExtractionServiceTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4...".getBytes();

    /** Scripted {@link ClaudeGateway}: each entry is a ClaudeMessage to return
     * or a RuntimeException to throw; records the structured flag per call. */
    static class FakeGateway implements ClaudeGateway {
        final Deque<Object> script = new ArrayDeque<>();
        final List<Boolean> structuredCalls = new ArrayList<>();
        final List<byte[]> contents = new ArrayList<>();

        FakeGateway returning(Object... steps) {
            for (Object step : steps) {
                script.add(step);
            }
            return this;
        }

        @Override
        public ClaudeMessage requestExtraction(byte[] content, String contentType, boolean structured) {
            structuredCalls.add(structured);
            contents.add(content);
            Object next = script.pop();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (ClaudeMessage) next;
        }

        int callCount() {
            return structuredCalls.size();
        }
    }

    private static ClaudeApiException apiError(ClaudeApiException.Kind kind, String message) {
        return new ClaudeApiException(kind, message, null);
    }

    private static ClaudeApiException grammarTooLargeError() {
        return apiError(
                ClaudeApiException.Kind.BAD_REQUEST,
                "The compiled grammar is too large. Please reduce the number of strict tools");
    }

    private static String minimalJson() {
        return TestFixtures.minimalSdsPayload().toString();
    }

    private ExtractionService service(FakeGateway gateway) {
        return new ExtractionService(gateway, TestFixtures.settings());
    }

    private ExtractionService structuredService(FakeGateway gateway) {
        return new ExtractionService(gateway, TestFixtures.structuredOutputsSettings());
    }

    @BeforeEach
    void resetGrammarFlag() {
        // The grammar-too-large fallback flag is process-local state; make
        // sure one test tripping it can't leak into another.
        ExtractionService.resetGrammarTooLarge();
    }

    // --- happy path ---------------------------------------------------------

    @Test
    void successReturnsResultWithoutWarnings() {
        FakeGateway gateway =
                new FakeGateway().returning(TestFixtures.fakeMessage(minimalJson(), "end_turn"));

        SdsExtractionResponse result =
                service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-1");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.model()).isEqualTo("claude-opus-4-8");
        assertThat(result.usage().inputTokens()).isEqualTo(1000);
        assertThat(result.usage().cacheCreationInputTokens()).isEqualTo(200);
        assertThat(result.data().schemaVersion).isEqualTo("2.1");
        // Default mode: structured outputs are off (the SDS schema exceeds
        // the API's compiled-grammar limits — see Prompts), so the schema is
        // embedded in the (cached) system prompt and enforced post-hoc.
        assertThat(gateway.structuredCalls).containsExactly(false);
    }

    @Test
    void structuredOutputsAreRequestedWhenEnabled() {
        FakeGateway gateway =
                new FakeGateway().returning(TestFixtures.fakeMessage(minimalJson(), "end_turn"));

        SdsExtractionResponse result =
                structuredService(gateway).extractSds(PDF_BYTES, "application/pdf", "req-1b");

        assertThat(result.warnings()).isEmpty();
        assertThat(gateway.structuredCalls).containsExactly(true);
    }

    @Test
    void wrappingCodeFenceIsStripped() {
        String fenced = "```json\n" + minimalJson() + "\n```";
        FakeGateway gateway = new FakeGateway().returning(TestFixtures.fakeMessage(fenced, "end_turn"));

        SdsExtractionResponse result =
                service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-3");

        assertThat(result.data().schemaVersion).isEqualTo("2.1");
    }

    // --- stop_reason branches ------------------------------------------------

    @Test
    void refusalThrowsRefusalException() {
        FakeGateway gateway =
                new FakeGateway().returning(TestFixtures.fakeMessage("", "refusal", "cyber"));

        assertThatExceptionOfType(ClaudeRefusalException.class)
                .isThrownBy(() -> service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-3"))
                .satisfies(exc -> assertThat(exc.details().get("category")).isEqualTo("cyber"));
    }

    @Test
    void refusalWithoutStopDetailsHasNullCategory() {
        // The API may omit stop_details on a refusal; the category is then
        // null rather than an error.
        FakeGateway gateway = new FakeGateway().returning(TestFixtures.fakeMessage("", "refusal"));

        assertThatExceptionOfType(ClaudeRefusalException.class)
                .isThrownBy(() -> service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-3b"))
                .satisfies(exc -> assertThat(exc.details().get("category")).isNull());
    }

    @Test
    void maxTokensWithInvalidJsonThrowsTruncated() {
        FakeGateway gateway =
                new FakeGateway()
                        .returning(TestFixtures.fakeMessage("{\"incomplete\": tr", "max_tokens"));

        assertThatExceptionOfType(ClaudeTruncatedException.class)
                .isThrownBy(() -> service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-4"));

        // Retrying a max_tokens truncation would just truncate again.
        assertThat(gateway.callCount()).isEqualTo(1);
    }

    @Test
    void maxTokensWithValidJsonReturnsWarning() {
        FakeGateway gateway =
                new FakeGateway().returning(TestFixtures.fakeMessage(minimalJson(), "max_tokens"));

        SdsExtractionResponse result =
                service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-5");

        assertThat(result.warnings()).containsExactly("output_truncated_max_tokens");
    }

    // --- invalid-response retry ----------------------------------------------

    @Test
    void invalidJsonIsRetriedOnceThenThrows() {
        FakeGateway gateway =
                new FakeGateway()
                        .returning(
                                TestFixtures.fakeMessage("not json at all", "end_turn"),
                                TestFixtures.fakeMessage("not json at all", "end_turn"));

        assertThatExceptionOfType(ClaudeResponseInvalidException.class)
                .isThrownBy(() -> service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-6"));

        // One automatic retry before giving up with extraction_invalid_response.
        assertThat(gateway.callCount()).isEqualTo(2);
    }

    @Test
    void invalidJsonRetrySuccessAddsWarningAndSumsUsage() {
        FakeGateway gateway =
                new FakeGateway()
                        .returning(
                                TestFixtures.fakeMessage("not json at all", "end_turn"),
                                TestFixtures.fakeMessage(minimalJson(), "end_turn"));

        SdsExtractionResponse result =
                service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-6b");

        assertThat(gateway.callCount()).isEqualTo(2);
        assertThat(result.warnings()).containsExactly("retried_invalid_response");
        // Usage must cover both API calls, not just the successful one.
        assertThat(result.usage().inputTokens()).isEqualTo(2000);
        assertThat(result.usage().outputTokens()).isEqualTo(1000);
        assertThat(result.usage().cacheCreationInputTokens()).isEqualTo(400);
    }

    @Test
    void refusalOnRetryThrowsRefusalException() {
        FakeGateway gateway =
                new FakeGateway()
                        .returning(
                                TestFixtures.fakeMessage("not json at all", "end_turn"),
                                TestFixtures.fakeMessage("", "refusal", "cyber"));

        assertThatExceptionOfType(ClaudeRefusalException.class)
                .isThrownBy(() -> service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-6d"));
    }

    // --- malformed response content -------------------------------------------

    @Test
    void responseWithoutTextBlockThrowsInvalidResponse() {
        FakeGateway gateway = new FakeGateway().returning(TestFixtures.fakeMessage(null, "end_turn"));

        assertThatExceptionOfType(ClaudeResponseInvalidException.class)
                .isThrownBy(() -> service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-13"));

        // A structurally unusable response (no text at all) isn't retried —
        // only a text response that fails schema validation is.
        assertThat(gateway.callCount()).isEqualTo(1);
    }

    @Test
    void unknownFieldInResponseFailsValidation() {
        ObjectNode payload = TestFixtures.minimalSdsPayload();
        payload.put("unexpected_field", "should not be allowed");
        FakeGateway gateway =
                new FakeGateway()
                        .returning(
                                TestFixtures.fakeMessage(payload.toString(), "end_turn"),
                                TestFixtures.fakeMessage(payload.toString(), "end_turn"));

        assertThatExceptionOfType(ClaudeResponseInvalidException.class)
                .isThrownBy(() -> service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-13b"));
    }

    @Test
    void missingRequiredSectionFailsValidation() {
        ObjectNode payload = TestFixtures.minimalSdsPayload();
        payload.remove("section_4_first_aid");
        FakeGateway gateway =
                new FakeGateway()
                        .returning(
                                TestFixtures.fakeMessage(payload.toString(), "end_turn"),
                                TestFixtures.fakeMessage(payload.toString(), "end_turn"));

        assertThatExceptionOfType(ClaudeResponseInvalidException.class)
                .isThrownBy(() -> service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-13c"));
    }

    // --- multi-SDS detection ---------------------------------------------------

    @Test
    void additionalDocumentsAddDetectionWarning() {
        ObjectNode payload = TestFixtures.minimalSdsPayload();
        payload.set(
                "additional_documents",
                TestFixtures.MAPPER
                        .createArrayNode()
                        .add(
                                TestFixtures.MAPPER
                                        .createObjectNode()
                                        .put("product_name", "B剤")
                                        .put("start_page", 6)
                                        .put("end_page", 11)));
        FakeGateway gateway =
                new FakeGateway().returning(TestFixtures.fakeMessage(payload.toString(), "end_turn"));

        SdsExtractionResponse result =
                service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-6f");

        assertThat(result.warnings()).containsExactly("additional_sds_documents_detected");
        assertThat(result.data().additionalDocuments.get(0).startPage).isEqualTo(6);
    }

    // --- domain post-validation warnings ---------------------------------------

    @Test
    void domainWarningsAreAppended() {
        ObjectNode payload = TestFixtures.minimalSdsPayload();
        ((ObjectNode) payload.get("section_3_composition"))
                .set(
                        "ingredients",
                        TestFixtures.MAPPER
                                .createArrayNode()
                                .add(
                                        TestFixtures.MAPPER
                                                .createObjectNode()
                                                .put("substance_name", "トルエン")
                                                .put("cas_number", "108-88-4"))); // bad check digit
        ((ObjectNode) payload.get("section_2_hazards_identification"))
                .set(
                        "pictograms",
                        TestFixtures.MAPPER.createArrayNode().add("GHS02").add("GHS99"));
        FakeGateway gateway =
                new FakeGateway().returning(TestFixtures.fakeMessage(payload.toString(), "end_turn"));

        SdsExtractionResponse result =
                service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-6e");

        assertThat(result.warnings())
                .containsExactly("invalid_cas_number:108-88-4", "unknown_pictogram:GHS99");
    }

    // --- structured-outputs grammar-size fallback -------------------------------

    @Test
    void grammarSizeErrorFallsBackToEmbeddedSchema() {
        FakeGateway gateway =
                new FakeGateway()
                        .returning(
                                grammarTooLargeError(),
                                TestFixtures.fakeMessage(minimalJson(), "end_turn"));

        SdsExtractionResponse result =
                structuredService(gateway).extractSds(PDF_BYTES, "application/pdf", "req-9");

        assertThat(result.warnings()).containsExactly("structured_outputs_unavailable");
        assertThat(gateway.structuredCalls).containsExactly(true, false);
    }

    @Test
    void grammarSizeErrorIsRememberedAcrossRequests() {
        FakeGateway first =
                new FakeGateway()
                        .returning(
                                grammarTooLargeError(),
                                TestFixtures.fakeMessage(minimalJson(), "end_turn"));
        structuredService(first).extractSds(PDF_BYTES, "application/pdf", "req-10a");

        // Second request goes straight to the fallback path: one call without
        // structured outputs, but the response still notes that structured
        // outputs were requested and unusable.
        FakeGateway second =
                new FakeGateway().returning(TestFixtures.fakeMessage(minimalJson(), "end_turn"));
        SdsExtractionResponse result =
                structuredService(second).extractSds(PDF_BYTES, "application/pdf", "req-10b");

        assertThat(result.warnings()).containsExactly("structured_outputs_unavailable");
        assertThat(second.structuredCalls).containsExactly(false);
    }

    @Test
    void invalidJsonRetryKeepsStructuredOutputs() {
        // When the first structured-outputs response fails schema validation,
        // the automatic retry must stay on the structured-outputs path —
        // validation failure is not a grammar-size failure.
        FakeGateway gateway =
                new FakeGateway()
                        .returning(
                                TestFixtures.fakeMessage("not json at all", "end_turn"),
                                TestFixtures.fakeMessage(minimalJson(), "end_turn"));

        SdsExtractionResponse result =
                structuredService(gateway).extractSds(PDF_BYTES, "application/pdf", "req-14");

        assertThat(result.warnings()).containsExactly("retried_invalid_response");
        assertThat(gateway.structuredCalls).containsExactly(true, true);
    }

    @Test
    void grammarSizeErrorOnRetryCallFallsBack() {
        // The grammar-size 400 can also surface on the validation-failure
        // retry (e.g. the API tightened its limit mid-process); the retry
        // itself must then fall back to the prompt-embedded schema and still
        // succeed.
        FakeGateway gateway =
                new FakeGateway()
                        .returning(
                                TestFixtures.fakeMessage("not json at all", "end_turn"),
                                grammarTooLargeError(),
                                TestFixtures.fakeMessage(minimalJson(), "end_turn"));

        SdsExtractionResponse result =
                structuredService(gateway).extractSds(PDF_BYTES, "application/pdf", "req-15");

        assertThat(result.warnings())
                .containsExactly("retried_invalid_response", "structured_outputs_unavailable");
        assertThat(gateway.structuredCalls).containsExactly(true, true, false);
    }

    @Test
    void nonGrammarBadRequestDoesNotFallBack() {
        FakeGateway gateway =
                new FakeGateway()
                        .returning(apiError(ClaudeApiException.Kind.BAD_REQUEST, "bad request"));

        assertThatExceptionOfType(ClaudeInvalidDocumentException.class)
                .isThrownBy(
                        () -> structuredService(gateway).extractSds(PDF_BYTES, "application/pdf", "req-11"));

        assertThat(gateway.callCount()).isEqualTo(1);

        // The flag must not have been tripped: the next request still tries
        // structured outputs.
        FakeGateway next =
                new FakeGateway().returning(TestFixtures.fakeMessage(minimalJson(), "end_turn"));
        structuredService(next).extractSds(PDF_BYTES, "application/pdf", "req-11b");
        assertThat(next.structuredCalls).containsExactly(true);
    }

    // --- upstream error mapping -------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "RATE_LIMIT, 503",
        "SERVER_ERROR, 503",
        "CONNECTION, 503",
        "CONFIG, 500",
        "OTHER, 500"
    })
    void apiErrorsMapToUpstreamError(ClaudeApiException.Kind kind, int expectedStatus) {
        FakeGateway gateway = new FakeGateway().returning(apiError(kind, "kaboom"));

        assertThatExceptionOfType(ClaudeUpstreamException.class)
                .isThrownBy(() -> service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-7"))
                .satisfies(exc -> assertThat(exc.statusCode()).isEqualTo(expectedStatus));
    }

    @Test
    void badRequestMapsToInvalidDocumentError() {
        FakeGateway gateway =
                new FakeGateway()
                        .returning(apiError(ClaudeApiException.Kind.BAD_REQUEST, "bad request"));

        assertThatExceptionOfType(ClaudeInvalidDocumentException.class)
                .isThrownBy(() -> service(gateway).extractSds(PDF_BYTES, "application/pdf", "req-7b"))
                .satisfies(exc -> assertThat(exc.statusCode()).isEqualTo(400));
    }

    @Test
    void badRequestOnGrammarFallbackCallMapsToInvalidDocumentError() {
        // The grammar-size 400 on the first call triggers the fallback retry
        // (without structured outputs); if THAT call also gets rejected as an
        // unprocessable document, it must still surface as invalid_document
        // (400), not fall through to a generic upstream error (500).
        FakeGateway gateway =
                new FakeGateway()
                        .returning(
                                grammarTooLargeError(),
                                apiError(ClaudeApiException.Kind.BAD_REQUEST, "document rejected"));

        assertThatExceptionOfType(ClaudeInvalidDocumentException.class)
                .isThrownBy(
                        () -> structuredService(gateway).extractSds(PDF_BYTES, "application/pdf", "req-16"))
                .satisfies(exc -> assertThat(exc.statusCode()).isEqualTo(400));

        assertThat(gateway.structuredCalls).containsExactly(true, false);
    }
}
