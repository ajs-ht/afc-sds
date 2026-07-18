package jp.co.ajs.afcsds.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jp.co.ajs.afcsds.TestFixtures;
import jp.co.ajs.afcsds.service.ClaudeGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Upload-limit behavior with tightened MAX_UPLOAD_MB / MAX_PDF_PAGES. */
@SpringBootTest(
        properties = {
            "afc-sds.anthropic-api-key=test-anthropic-key",
            "afc-sds.api-key=test-api-key",
            "afc-sds.max-upload-mb=1",
            "afc-sds.max-pdf-pages=2"
        })
@AutoConfigureMockMvc
class ExtractEndpointLimitsTest {

    private static final String API_KEY = "test-api-key";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ClaudeGateway claudeGateway;

    @Test
    void oversizedFileReturns400() throws Exception {
        byte[] oversized = new byte[2 * 1024 * 1024 + 16];
        System.arraycopy("%PDF-1.4\n".getBytes(), 0, oversized, 0, 9);

        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(
                                        new MockMultipartFile(
                                                "file", "big.pdf", "application/pdf", oversized))
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("file_too_large"));
    }

    @Test
    void oversizedPdfWithPagesReturns400BeforeSlicing() throws Exception {
        // Regression test: an oversized upload combined with `pages` must be
        // rejected by validateUpload() before slicePdfPages() ever parses it.
        byte[] pdf = TestFixtures.pdfWithPages(3);
        byte[] oversized = new byte[pdf.length + 2 * 1024 * 1024];
        System.arraycopy(pdf, 0, oversized, 0, pdf.length);

        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(
                                        new MockMultipartFile(
                                                "file", "big.pdf", "application/pdf", oversized))
                                .param("pages", "1")
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("file_too_large"));
    }

    @Test
    void tooManyPagesReturns400() throws Exception {
        mockMvc.perform(
                        multipart("/v1/sds/extract")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "multi.pdf",
                                                "application/pdf",
                                                TestFixtures.pdfWithPages(3)))
                                .header("X-API-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("too_many_pages"));
    }
}
