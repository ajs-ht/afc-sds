package jp.co.ajs.afcsds.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App configuration, loaded from environment variables via application.yml.
 *
 * <p>{@code .env.example} is the source of truth for which variables are
 * settable and what each one does. {@code ALLOWED_MIME_TYPES} has no env-var
 * counterpart (config-only) and can only be changed by editing the constant
 * here.
 */
@ConfigurationProperties(prefix = "afc-sds")
public record AppSettings(
        String anthropicApiKey,
        String modelId,
        String apiKey,
        int maxUploadMb,
        int maxPdfPages,
        long maxOutputTokens,
        int maxConcurrentExtractions,
        int anthropicMaxRetries,
        boolean useStructuredOutputs,
        String logLevel,
        String logFormat) {

    public AppSettings {
        // A blank (not just unset) secret would otherwise authenticate any
        // request carrying an equally-blank X-API-Key header/value — fail
        // fast at startup instead of silently accepting the misconfiguration.
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("afc-sds.api-key must not be blank.");
        }
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            throw new IllegalStateException("afc-sds.anthropic-api-key must not be blank.");
        }
        // A zero or negative limit is always a typo in the environment; it
        // would surface later as inexplicable rejections (or a Semaphore that
        // admits nothing), so fail fast at startup like the secrets above.
        requirePositive("afc-sds.max-upload-mb", maxUploadMb);
        requirePositive("afc-sds.max-pdf-pages", maxPdfPages);
        requirePositive("afc-sds.max-output-tokens", maxOutputTokens);
        requirePositive("afc-sds.max-concurrent-extractions", maxConcurrentExtractions);
        if (anthropicMaxRetries < 0) {
            throw new IllegalStateException("afc-sds.anthropic-max-retries must not be negative.");
        }
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalStateException(name + " must be positive (was " + value + ").");
        }
    }

    public static final Set<String> ALLOWED_MIME_TYPES =
            Set.of("application/pdf", "image/png", "image/jpeg", "image/webp");

    // Slack above the file-size limit for multipart framing (boundaries, part
    // headers, the optional `pages` field), so a file just under MAX_UPLOAD_MB
    // isn't rejected because of request overhead. Shared by the servlet-level
    // request cap (WebConfig) and the Content-Length pre-check
    // (FileValidation.checkContentLength); the per-file limit itself is still
    // enforced exactly by validateUpload().
    private static final long MULTIPART_SLACK_BYTES = 2L * 1024 * 1024;

    public long maxUploadBytes() {
        return (long) maxUploadMb * 1024 * 1024;
    }

    /** Whole-request byte cap: the file limit plus multipart framing slack. */
    public long maxRequestBytes() {
        return maxUploadBytes() + MULTIPART_SLACK_BYTES;
    }
}
