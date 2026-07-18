package jp.co.ajs.afcsds.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jp.co.ajs.afcsds.TestFixtures;
import jp.co.ajs.afcsds.service.ClaudeApiException;
import jp.co.ajs.afcsds.service.ClaudeGateway;
import jp.co.ajs.afcsds.service.ExtractionServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * USE_STRUCTURED_OUTPUTS=true wired through the full HTTP stack: the property
 * must reach the gateway as {@code structured=true}, and the grammar-size
 * fallback must surface its warning in the JSON response.
 */
@SpringBootTest(
        properties = {
            "afc-sds.anthropic-api-key=test-anthropic-key",
            "afc-sds.api-key=test-api-key",
            "afc-sds.use-structured-outputs=true"
        })
@AutoConfigureMockMvc
class ExtractStructuredOutputsTest {

    private static final String API_KEY = "test-api-key";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ClaudeGateway claudeGateway;

    @BeforeEach
    void resetGrammarFlag() {
        // The grammar fallback flag is process-local; another test class (or
        // the fallback test below) tripping it must not leak in or out.
        ExtractionServiceTestSupport.resetGrammarTooLarge();
    }

    private static MockMultipartFile pdfFile() {
        return new MockMultipartFile(
                "file", "sample_sds.pdf", "application/pdf", TestFixtures.samplePdfBytes());
    }

    @Test
    void structuredOutputsPropertyReachesTheGateway() throws Exception {
        when(claudeGateway.requestExtraction(any(), anyString(), anyBoolean()))
                .thenReturn(
                        TestFixtures.fakeMessage(
                                TestFixtures.minimalSdsPayload().toString(), "end_turn"));

        mockMvc.perform(multipart("/v1/sds/extract").file(pdfFile()).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings").isEmpty());

        verify(claudeGateway).requestExtraction(any(), anyString(), eq(true));
    }

    @Test
    void grammarFallbackWarningSurfacesInTheHttpResponse() throws Exception {
        when(claudeGateway.requestExtraction(any(), anyString(), anyBoolean()))
                .thenThrow(
                        new ClaudeApiException(
                                ClaudeApiException.Kind.BAD_REQUEST,
                                "The compiled grammar is too large. Please reduce the number of "
                                        + "strict tools",
                                null))
                .thenReturn(
                        TestFixtures.fakeMessage(
                                TestFixtures.minimalSdsPayload().toString(), "end_turn"));

        mockMvc.perform(multipart("/v1/sds/extract").file(pdfFile()).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0]").value("structured_outputs_unavailable"));

        verify(claudeGateway).requestExtraction(any(), anyString(), eq(true));
        verify(claudeGateway).requestExtraction(any(), anyString(), eq(false));
    }
}
