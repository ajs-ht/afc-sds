package jp.co.ajs.afcsds.validation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jp.co.ajs.afcsds.config.AppSettings;
import jp.co.ajs.afcsds.core.AppExceptions.EmptyFileException;
import jp.co.ajs.afcsds.core.AppExceptions.FileTooLargeException;
import jp.co.ajs.afcsds.core.AppExceptions.InvalidPageRangeException;
import jp.co.ajs.afcsds.core.AppExceptions.TooManyPagesException;
import jp.co.ajs.afcsds.core.AppExceptions.UnsupportedFileTypeException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PageExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Upload validation: size/MIME/PDF-page-count checks, and the {@code pages}
 * slicer.
 *
 * <p>{@link #checkContentLength} is a best-effort pre-check on the
 * Content-Length header; {@link #validateUpload} re-checks after the body has
 * been read (a spoofed Content-Length can't skip validation).
 * {@link #slicePdfPages} backs the optional {@code pages} form field used to
 * re-extract further documents from a multi-SDS PDF.
 */
public final class FileValidation {

    private FileValidation() {}

    public static final String PDF_MIME_TYPE = "application/pdf";

    // `pages` form field: "6" (one page) or "6-11" (inclusive range), 1-based.
    private static final Pattern PAGES_SPEC = Pattern.compile("^\\s*(\\d+)\\s*(?:-\\s*(\\d+)\\s*)?$");

    // Magic-byte signatures for the MIME types this API accepts, so a spoofed
    // Content-Type header can't smuggle unexpected content past
    // validateUpload(). WEBP is a RIFF container: the fourCC at byte offset 8
    // identifies it.
    private static final Map<String, List<byte[]>> MAGIC_SIGNATURES =
            Map.of(
                    "application/pdf", List.of("%PDF-".getBytes()),
                    "image/png", List.of(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}),
                    "image/jpeg", List.of(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}),
                    "image/webp", List.of("RIFF".getBytes()));

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOfRange(content, 0, prefix.length), prefix);
    }

    private static boolean hasValidSignature(byte[] content, String contentType) {
        List<byte[]> signatures = MAGIC_SIGNATURES.get(contentType);
        if (signatures == null) {
            return true;
        }
        if (signatures.stream().noneMatch(sig -> startsWith(content, sig))) {
            return false;
        }
        if ("image/webp".equals(contentType)) {
            return content.length >= 12
                    && Arrays.equals(
                            Arrays.copyOfRange(content, 8, 12), "WEBP".getBytes());
        }
        return true;
    }

    /**
     * Reject an oversized request before its body is processed, when possible.
     *
     * <p>This is a best-effort check based on the Content-Length header
     * (present for ordinary multipart uploads). If the header is absent or
     * malformed we skip it silently — {@link #validateUpload} still catches an
     * oversized body after it has been read. The header covers the whole
     * multipart body (boundaries, part headers, the {@code pages} field), so
     * it is compared against {@link AppSettings#maxRequestBytes()} — the file
     * limit plus framing slack — not the bare file limit, which would falsely
     * reject a file just under MAX_UPLOAD_MB.
     */
    public static void checkContentLength(String contentLengthHeader, AppSettings settings) {
        if (contentLengthHeader == null) {
            return;
        }

        long contentLength;
        try {
            contentLength = Long.parseLong(contentLengthHeader.trim());
        } catch (NumberFormatException e) {
            return;
        }

        if (contentLength > settings.maxRequestBytes()) {
            throw new FileTooLargeException(
                    "Request body of %d bytes exceeds the %dMB upload limit."
                            .formatted(contentLength, settings.maxUploadMb()),
                    Map.of("size_bytes", contentLength, "max_bytes", settings.maxUploadBytes()));
        }
    }

    /**
     * Validate an uploaded SDS file before it is sent to Claude.
     *
     * <p>Throws {@link jp.co.ajs.afcsds.core.AppException} subclasses on any
     * violation.
     */
    public static void validateUpload(byte[] content, String contentType, AppSettings settings) {
        if (content == null || content.length == 0) {
            throw new EmptyFileException("Uploaded file is empty.");
        }

        if (contentType == null || !AppSettings.ALLOWED_MIME_TYPES.contains(contentType)) {
            throw new UnsupportedFileTypeException(
                    "Unsupported file type: '%s'. Allowed types: %s."
                            .formatted(contentType, new TreeSet<>(AppSettings.ALLOWED_MIME_TYPES)),
                    mapOfNullable("content_type", contentType));
        }

        if (!hasValidSignature(content, contentType)) {
            throw new UnsupportedFileTypeException(
                    "File content does not match the declared type '%s'.".formatted(contentType),
                    mapOfNullable("content_type", contentType));
        }

        if (content.length > settings.maxUploadBytes()) {
            throw new FileTooLargeException(
                    "File size %d bytes exceeds the %dMB limit."
                            .formatted(content.length, settings.maxUploadMb()),
                    Map.of("size_bytes", content.length, "max_bytes", settings.maxUploadBytes()));
        }

        if (PDF_MIME_TYPE.equals(contentType)) {
            int pageCount = countPdfPages(content);
            if (pageCount > settings.maxPdfPages()) {
                throw new TooManyPagesException(
                        "PDF has %d pages, exceeding the %d-page limit."
                                .formatted(pageCount, settings.maxPdfPages()),
                        Map.of("page_count", pageCount, "max_pages", settings.maxPdfPages()));
            }
        }
    }

    /**
     * Return a new PDF containing only the pages named by {@code pagesSpec}.
     *
     * <p>{@code pagesSpec} is "N" or "N-M" (1-based, inclusive) — the format
     * callers get back in {@code additional_documents} for multi-SDS files.
     * Throws {@link InvalidPageRangeException} for a non-PDF upload, a
     * malformed spec, or a range outside the document.
     */
    public static byte[] slicePdfPages(byte[] content, String contentType, String pagesSpec) {
        if (!PDF_MIME_TYPE.equals(contentType)) {
            throw new InvalidPageRangeException(
                    "The `pages` parameter is only supported for PDF uploads.",
                    mapOfNullable("content_type", contentType));
        }

        Matcher match = PAGES_SPEC.matcher(pagesSpec);
        if (!match.matches()) {
            throw new InvalidPageRangeException(
                    "Invalid `pages` value: '%s'. Use \"6\" or \"6-11\" (1-based, inclusive)."
                            .formatted(pagesSpec),
                    Map.of("pages", pagesSpec));
        }
        int start = Integer.parseInt(match.group(1));
        int end = match.group(2) != null ? Integer.parseInt(match.group(2)) : start;

        try (PDDocument document = loadPdf(content)) {
            int pageCount = document.getNumberOfPages();
            if (!(1 <= start && start <= end && end <= pageCount)) {
                throw new InvalidPageRangeException(
                        "Page range %d-%d is out of bounds for a %d-page PDF."
                                .formatted(start, end, pageCount),
                        Map.of("pages", pagesSpec, "page_count", pageCount));
            }

            try (PDDocument sliced = new PageExtractor(document, start, end).extract();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                sliced.save(buffer);
                return buffer.toByteArray();
            }
        } catch (IOException e) {
            throw new UnsupportedFileTypeException("Could not parse PDF: " + e.getMessage());
        }
    }

    private static int countPdfPages(byte[] content) {
        try (PDDocument document = loadPdf(content)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new UnsupportedFileTypeException("Could not parse PDF: " + e.getMessage());
        }
    }

    private static PDDocument loadPdf(byte[] content) throws IOException {
        return Loader.loadPDF(content);
    }

    private static Map<String, Object> mapOfNullable(String key, Object value) {
        // Map.of rejects null values; details may legitimately carry one.
        java.util.HashMap<String, Object> map = new java.util.HashMap<>();
        map.put(key, value);
        return map;
    }
}
