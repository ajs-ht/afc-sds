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
    }

    public static final Set<String> ALLOWED_MIME_TYPES =
            Set.of("application/pdf", "image/png", "image/jpeg", "image/webp");

    public long maxUploadBytes() {
        return (long) maxUploadMb * 1024 * 1024;
    }
}
