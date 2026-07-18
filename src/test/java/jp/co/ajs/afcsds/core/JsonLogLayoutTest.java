package jp.co.ajs.afcsds.core;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonLogLayoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LoggingEvent event(String loggerName, Level level, String message, Throwable t) {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger(loggerName);
        return new LoggingEvent("test", logger, level, message, t, null);
    }

    @Test
    void emitsValidJson() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        String line =
                layout.doLayout(
                        event("afc_sds.access", Level.INFO, "request request_id=abc status=200", null));

        JsonNode entry = MAPPER.readTree(line);
        assertThat(entry.get("level").asText()).isEqualTo("INFO");
        assertThat(entry.get("logger").asText()).isEqualTo("afc_sds.access");
        assertThat(entry.get("message").asText()).isEqualTo("request request_id=abc status=200");
        assertThat(entry.has("time")).isTrue();
        assertThat(entry.has("exc_info")).isFalse();
    }

    @Test
    void includesExcInfo() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        String line =
                layout.doLayout(
                        event("afc_sds", Level.ERROR, "internal_error", new IllegalStateException("boom")));

        JsonNode entry = MAPPER.readTree(line);
        assertThat(entry.get("exc_info").asText()).contains("IllegalStateException").contains("boom");
    }

    @Test
    void preservesNonAscii() throws Exception {
        JsonLogLayout layout = new JsonLogLayout();
        String line = layout.doLayout(event("afc_sds", Level.INFO, "抽出完了", null));

        assertThat(line).contains("抽出完了");
        assertThat(MAPPER.readTree(line).get("message").asText()).isEqualTo("抽出完了");
    }
}
