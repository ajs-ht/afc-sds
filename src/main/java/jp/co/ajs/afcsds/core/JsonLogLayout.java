package jp.co.ajs.afcsds.core;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Single-line JSON log records for machine-readable log pipelines (LOG_FORMAT=json). */
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZone(ZoneId.systemDefault());

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", TIME_FORMAT.format(Instant.ofEpochMilli(event.getTimeStamp())));
        entry.put("level", event.getLevel().toString());
        entry.put("logger", event.getLoggerName());
        // Set by RequestIdFilter for every in-request log line, so pipelines
        // can filter by request without parsing it out of the message text.
        String requestId = event.getMDCPropertyMap().get("request_id");
        if (requestId != null) {
            entry.put("request_id", requestId);
        }
        entry.put("message", event.getFormattedMessage());
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            entry.put("exc_info", ThrowableProxyUtil.asString(throwable));
        }
        try {
            return MAPPER.writeValueAsString(entry) + CoreConstants.LINE_SEPARATOR;
        } catch (Exception e) {
            return entry + CoreConstants.LINE_SEPARATOR;
        }
    }
}
