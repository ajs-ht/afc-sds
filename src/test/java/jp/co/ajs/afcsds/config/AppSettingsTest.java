package jp.co.ajs.afcsds.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppSettingsTest {

    private static AppSettings settingsWith(String apiKey, String anthropicApiKey) {
        return new AppSettings(
                anthropicApiKey, "claude-opus-4-8", apiKey, 32, 50, 24000, 8, 2, false, "INFO", "text");
    }

    private static AppSettings settingsWithLimits(
            int maxUploadMb,
            int maxPdfPages,
            long maxOutputTokens,
            int maxConcurrentExtractions,
            int anthropicMaxRetries) {
        return new AppSettings(
                "a",
                "claude-opus-4-8",
                "k",
                maxUploadMb,
                maxPdfPages,
                maxOutputTokens,
                maxConcurrentExtractions,
                anthropicMaxRetries,
                false,
                "INFO",
                "text");
    }

    @Test
    void nonBlankSecretsAreAccepted() {
        assertThatCode(() -> settingsWith("k", "a")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankApiKeyIsRejected(String blank) {
        // A blank API_KEY would otherwise let any request carrying an
        // equally-blank X-API-Key header/value authenticate.
        assertThatIllegalStateException().isThrownBy(() -> settingsWith(blank, "a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankAnthropicApiKeyIsRejected(String blank) {
        assertThatIllegalStateException().isThrownBy(() -> settingsWith("k", blank));
    }

    @Test
    void nonPositiveLimitsAreRejectedAtStartup() {
        // A zero/negative limit is always an environment typo; it must fail
        // fast at startup, not surface later as inexplicable rejections.
        assertThatIllegalStateException()
                .isThrownBy(() -> settingsWithLimits(0, 50, 24000, 8, 2))
                .withMessageContaining("max-upload-mb");
        assertThatIllegalStateException()
                .isThrownBy(() -> settingsWithLimits(32, -1, 24000, 8, 2))
                .withMessageContaining("max-pdf-pages");
        assertThatIllegalStateException()
                .isThrownBy(() -> settingsWithLimits(32, 50, 0, 8, 2))
                .withMessageContaining("max-output-tokens");
        assertThatIllegalStateException()
                .isThrownBy(() -> settingsWithLimits(32, 50, 24000, 0, 2))
                .withMessageContaining("max-concurrent-extractions");
        assertThatIllegalStateException()
                .isThrownBy(() -> settingsWithLimits(32, 50, 24000, 8, -1))
                .withMessageContaining("anthropic-max-retries");
    }

    @Test
    void zeroRetriesIsAllowed() {
        // Retries can legitimately be disabled outright.
        assertThatCode(() -> settingsWithLimits(32, 50, 24000, 8, 0)).doesNotThrowAnyException();
    }

    @Test
    void maxRequestBytesAllowsMultipartSlackAboveTheFileLimit() {
        AppSettings settings = settingsWithLimits(32, 50, 24000, 8, 2);
        assertThat(settings.maxUploadBytes()).isEqualTo(32L * 1024 * 1024);
        // The whole-request cap leaves room for multipart framing so a file
        // just under MAX_UPLOAD_MB isn't rejected because of overhead.
        assertThat(settings.maxRequestBytes()).isEqualTo(settings.maxUploadBytes() + 2L * 1024 * 1024);
    }
}
