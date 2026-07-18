package jp.co.ajs.afcsds.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import jp.co.ajs.afcsds.config.AppSettings;
import jp.co.ajs.afcsds.core.AppExceptions.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Multi-key (rotation) behavior; the single-key happy/reject paths are also covered via MockMvc. */
class ApiKeyInterceptorTest {

    private static AppSettings settingsWithApiKey(String apiKey) {
        return new AppSettings(
                "a", "claude-opus-4-8", apiKey, 32, 50, 24000, 8, 2, 0, false, "INFO", "text");
    }

    private static boolean handle(ApiKeyInterceptor interceptor, String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader("X-API-Key", headerValue);
        }
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void everyConfiguredKeyAuthenticates() {
        ApiKeyInterceptor interceptor = new ApiKeyInterceptor(settingsWithApiKey("old-key, new-key"));

        assertThat(handle(interceptor, "old-key")).isTrue();
        assertThat(handle(interceptor, "new-key")).isTrue();
    }

    @Test
    void unknownKeyIsRejected() {
        ApiKeyInterceptor interceptor = new ApiKeyInterceptor(settingsWithApiKey("old-key, new-key"));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> handle(interceptor, "other-key"));
    }

    @Test
    void missingHeaderIsRejected() {
        ApiKeyInterceptor interceptor = new ApiKeyInterceptor(settingsWithApiKey("only-key"));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> handle(interceptor, null));
    }

    @Test
    void keyListedWithSurroundingWhitespaceStillMatchesExactly() {
        // The configured " new-key " is trimmed at parse time; the header
        // value itself is compared exactly (no trimming of client input).
        ApiKeyInterceptor interceptor = new ApiKeyInterceptor(settingsWithApiKey("old-key,  new-key "));

        assertThat(handle(interceptor, "new-key")).isTrue();
        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> handle(interceptor, " new-key "));
    }
}
