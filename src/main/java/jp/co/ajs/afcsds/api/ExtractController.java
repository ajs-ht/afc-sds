package jp.co.ajs.afcsds.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import jp.co.ajs.afcsds.config.AppSettings;
import jp.co.ajs.afcsds.schema.Responses.SdsExtractionResponse;
import jp.co.ajs.afcsds.service.ExtractionService;
import jp.co.ajs.afcsds.validation.FileValidation;
import jp.co.ajs.afcsds.web.RequestIdFilter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1")
public class ExtractController {

    private final AppSettings settings;
    private final ExtractionService extractionService;

    public ExtractController(AppSettings settings, ExtractionService extractionService) {
        this.settings = settings;
        this.extractionService = extractionService;
    }

    /**
     * Extract structured JSON from an uploaded SDS (PDF or image).
     *
     * <p>Returns the JIS Z 7253 16-section document as {@code data}, plus
     * {@code warnings} (schema-valid but suspicious values; never a
     * rejection) and {@code usage} (token counts). For a PDF containing
     * multiple concatenated SDS files, only the first is extracted; the rest
     * are reported in {@code data.additional_documents} and callers re-fetch
     * them by re-posting the same file with the optional 1-based {@code pages}
     * form field ({@code "6"} or {@code "6-11"}, inclusive).
     */
    @PostMapping(value = "/sds/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SdsExtractionResponse extractSds(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "pages", required = false) String pages,
            HttpServletRequest request) {

        // Reject an oversized request before buffering its body, when possible.
        FileValidation.checkContentLength(request.getHeader("Content-Length"), settings);

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read uploaded file", e);
        }
        String contentType = file.getContentType();

        FileValidation.validateUpload(content, contentType, settings);

        if (pages != null) {
            content = FileValidation.slicePdfPages(content, contentType, pages);
        }

        return extractionService.extractSds(
                content, contentType, RequestIdFilter.requestId(request));
    }
}
