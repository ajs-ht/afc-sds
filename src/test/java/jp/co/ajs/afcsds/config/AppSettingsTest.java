package jp.co.ajs.afcsds.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppSettingsTest {

    private static AppSettings settingsWith(String apiKey, String anthropicApiKey) {
        return new AppSettings(
                anthropicApiKey, "claude-opus-4-8", apiKey, 32, 50, 24000, false, "INFO", "text");
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
}
