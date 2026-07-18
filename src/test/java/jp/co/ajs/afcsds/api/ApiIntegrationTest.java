package jp.co.ajs.afcsds.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import jp.co.ajs.afcsds.TestFixtures;
import jp.co.ajs.afcsds.schema.SdsSchema;
import jp.co.ajs.afcsds.service.ClaudeApiException;
import jp.co.ajs.afcsds.service.ClaudeGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        properties = {
            "afc-sds.anthropic-api-key=test-anthropic-key",
            "afc-sds.api-key=test-api-key"
        })
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final String API_KEY = "test-api-key";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ClaudeGateway claudeGateway;

    private static MockMultipartFile pdfFile(byte[] bytes) {
        return new MockMultipartFile("file", "sample_sds.pdf", "application/pdf", bytes);
    }

    private void gatewayReturns(String text, String stopReason) {
        when(claudeGateway.requestExtraction(any(), anyString(), anyBoolean()))
                .thenReturn(TestFixtures.fakeMessage(text, stopReason));
    }

    // --- auth ---------------------------------------------------------------

    @Test
    void healthzNeedsNoAuth() throws Exception {
        mockMvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void missingApiKeyReturns401() throws Exception {
        mockMvc.perform(get("/v1/sds/schema"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("unauthorized"));
    }

    @Test
    void wrongApiKeyReturns401() throws Exception {
        mockMvc.perform(get("/v1/sds/schema").header("X-API-Key", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("unauthorized"));
    }

    // --- request context ----------------------------------------------------

    @Test
    void everyResponseCarriesXRequestId() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/healthz"))
                        .andExpect(status().isOk())
                        .andExpect(header().exists("X-Request-ID"))
                        .andReturn();
        // Must be a well-formed UUID so clients can rely on the format.
        UUID.fromString(result.getResponse().getHeader("X-Request-ID"));
    }

    @Test
    void errorBodyRequestIdMatchesHeader() throws Exception {
        MvcResult result =
                mockMvc.perform(multipart("/v1/sds/extract").header("X-API-Key", "wrong"))
                        .andExpect(status().isUnauthorized())
                        .andReturn();
        JsonNode body = TestFixtures.MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("error").get("request_id").asText())
                .isEqualTo(result.getResponse().getHeader("X-Request-ID"));
    }

    // --- framework-level errors keep the shared error body -------------------

    @Test
    void unknownPathReturns404NotFound() throws Exception {
        mockMvc.perform(get("/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("not_found"));
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(delete("/healthz"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.type").value("method_not_allowed"));
    }

    // --- schema endpoint ----------------------------------------------------

    @Test
    void schemaEndpointReturnsVersionedSchema() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/v1/sds/schema").header("X-API-Key", API_KEY))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.schema_version").value("2.1"))
                        .andReturn();
        JsonNode body = TestFixtures.MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("json_schema")).isEqualTo(SdsSchema.SCHEMA_NODE);
    }

    // --- extract endpoint ----------------------------------------------------

    @Test
    void extractHappyPathReturnsStructuredJson() throws Exception {
        ObjectNode payload = TestFixtures.minimalSdsPayload();
        ((ObjectNode) payload.get("section_1_product_and_company")).put("product_name", "テスト洗浄剤");
        gatewayReturns(payload.toString(), "end_turn");

        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(pdfFile(TestFixtures.samplePdfBytes()))
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.section_1_product_and_company.product_name").value("テスト洗浄剤"))
                .andExpect(jsonPath("$.warnings").isEmpty())
                .andExpect(jsonPath("$.usage.input_tokens").value(1000));
    }

    @Test
    void unsupportedFileTypeReturns400() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());
        mockMvc.perform(multipart("/v1/sds/extract").file(file).header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("unsupported_file_type"));
    }

    @Test
    void corruptPdfReturns400() throws Exception {
        // Valid %PDF- magic but unparsable structure (truncated download etc.).
        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(pdfFile("%PDF-1.7\nnot a real pdf body".getBytes()))
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("unsupported_file_type"));
    }

    @Test
    void emptyFileReturns400() throws Exception {
        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(pdfFile(new byte[0]))
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("empty_file"));
    }

    @Test
    void spoofedContentTypeReturns400() throws Exception {
        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(pdfFile("not actually a pdf".getBytes()))
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("unsupported_file_type"));
    }

    @Test
    void refusalReturns422() throws Exception {
        when(claudeGateway.requestExtraction(any(), anyString(), anyBoolean()))
                .thenReturn(TestFixtures.fakeMessage("", "refusal", "cyber"));

        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(pdfFile(TestFixtures.samplePdfBytes()))
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.type").value("extraction_refused"));
    }

    @Test
    void truncatedOutputReturns502() throws Exception {
        gatewayReturns("{\"incomplete\": tr", "max_tokens");

        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(pdfFile(TestFixtures.samplePdfBytes()))
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.type").value("extraction_truncated"));
    }

    @Test
    void maxTokensWithValidJsonReturnsWarning() throws Exception {
        gatewayReturns(TestFixtures.minimalSdsPayload().toString(), "max_tokens");

        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(pdfFile(TestFixtures.samplePdfBytes()))
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0]").value("output_truncated_max_tokens"));
    }

    @Test
    void pagesFieldSendsOnlySelectedPages() throws Exception {
        gatewayReturns(TestFixtures.minimalSdsPayload().toString(), "end_turn");

        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "multi_sds.pdf",
                                                "application/pdf",
                                                TestFixtures.pdfWithPages(11)))
                                .param("pages", "6-11")
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isOk());

        ArgumentCaptor<byte[]> sentPdf = ArgumentCaptor.forClass(byte[].class);
        verify(claudeGateway).requestExtraction(sentPdf.capture(), anyString(), anyBoolean());
        assertThat(TestFixtures.pdfPageCount(sentPdf.getValue())).isEqualTo(6);
    }

    @Test
    void multiSdsFlowExtractsFirstThenSelectedPages() throws Exception {
        // The documented caller flow for a multi-SDS PDF, end to end: the
        // first extraction reports the further document with its page range,
        // and re-posting the same file with `pages` sends only those pages.
        ObjectNode first = TestFixtures.minimalSdsPayload();
        first.set(
                "additional_documents",
                TestFixtures.MAPPER
                        .createArrayNode()
                        .add(
                                TestFixtures.MAPPER
                                        .createObjectNode()
                                        .put("product_name", "テスト洗浄剤B")
                                        .put("start_page", 6)
                                        .put("end_page", 11)));
        when(claudeGateway.requestExtraction(any(), anyString(), anyBoolean()))
                .thenReturn(
                        TestFixtures.fakeMessage(first.toString(), "end_turn"),
                        TestFixtures.fakeMessage(
                                TestFixtures.minimalSdsPayload().toString(), "end_turn"));

        MockMultipartFile multiSds =
                new MockMultipartFile(
                        "file", "multi_sds.pdf", "application/pdf", TestFixtures.pdfWithPages(11));

        mockMvc.perform(multipart("/v1/sds/extract").file(multiSds).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0]").value("additional_sds_documents_detected"))
                .andExpect(jsonPath("$.data.additional_documents[0].start_page").value(6))
                .andExpect(jsonPath("$.data.additional_documents[0].end_page").value(11));

        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(multiSds)
                                .param("pages", "6-11")
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings").isEmpty());

        ArgumentCaptor<byte[]> sentPdf = ArgumentCaptor.forClass(byte[].class);
        verify(claudeGateway, times(2))
                .requestExtraction(sentPdf.capture(), anyString(), anyBoolean());
        assertThat(TestFixtures.pdfPageCount(sentPdf.getAllValues().get(0))).isEqualTo(11);
        assertThat(TestFixtures.pdfPageCount(sentPdf.getAllValues().get(1))).isEqualTo(6);
    }

    @Test
    void invalidPagesReturns400() throws Exception {
        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "multi_sds.pdf",
                                                "application/pdf",
                                                TestFixtures.pdfWithPages(3)))
                                .param("pages", "2-9")
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_page_range"));
    }

    @Test
    void pagesOnImageReturns400() throws Exception {
        byte[] png = new byte[108];
        System.arraycopy(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, 0, png, 0, 8);
        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(new MockMultipartFile("file", "scan.png", "image/png", png))
                                .param("pages", "1")
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_page_range"));
    }

    @Test
    void upstreamErrorReturns503WithMatchingRequestId() throws Exception {
        when(claudeGateway.requestExtraction(any(), anyString(), anyBoolean()))
                .thenThrow(
                        new ClaudeApiException(ClaudeApiException.Kind.RATE_LIMIT, "rate limited", null));

        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/sds/extract")
                                        .file(pdfFile(TestFixtures.samplePdfBytes()))
                                        .header("X-API-Key", API_KEY))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.error.type").value("upstream_error"))
                        .andReturn();
        JsonNode body = TestFixtures.MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("error").get("request_id").asText())
                .isEqualTo(result.getResponse().getHeader("X-Request-ID"));
    }

    @Test
    void invalidDocumentReturns400() throws Exception {
        when(claudeGateway.requestExtraction(any(), anyString(), anyBoolean()))
                .thenThrow(
                        new ClaudeApiException(ClaudeApiException.Kind.BAD_REQUEST, "bad request", null));

        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(pdfFile(TestFixtures.samplePdfBytes()))
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_document"));
    }

    @Test
    void invalidResponseReturns502() throws Exception {
        gatewayReturns("not json at all", "end_turn");

        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(pdfFile(TestFixtures.samplePdfBytes()))
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.type").value("extraction_invalid_response"));
    }

    @Test
    void unexpectedErrorReturns500InternalError() throws Exception {
        // A bare, unmapped exception must still hit the catch-all handler
        // with the documented {"error": {...}} shape and an X-Request-ID header.
        when(claudeGateway.requestExtraction(any(), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("boom"));

        MvcResult result =
                mockMvc.perform(
                                multipart("/v1/sds/extract")
                                        .file(pdfFile(TestFixtures.samplePdfBytes()))
                                        .header("X-API-Key", API_KEY))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.error.type").value("internal_error"))
                        .andReturn();
        JsonNode body = TestFixtures.MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("error").get("request_id").asText())
                .isEqualTo(result.getResponse().getHeader("X-Request-ID"));
    }

    @Test
    void missingFileReturnsValidationError() throws Exception {
        MvcResult result =
                mockMvc.perform(multipart("/v1/sds/extract").header("X-API-Key", API_KEY))
                        .andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.error.type").value("validation_error"))
                        .andReturn();
        JsonNode body = TestFixtures.MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("error").get("request_id").asText())
                .isEqualTo(result.getResponse().getHeader("X-Request-ID"));
    }
}
