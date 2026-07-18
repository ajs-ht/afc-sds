package jp.co.ajs.afcsds.core;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Access / token-usage log lines with the same field layout as the Python
 * version, so existing log pipelines and grep habits keep working.
 */
public final class AppLogs {

    private static final Logger USAGE = LoggerFactory.getLogger("afc_sds.usage");
    private static final Logger ACCESS = LoggerFactory.getLogger("afc_sds.access");

    private AppLogs() {}

    public static void logUsage(
            String requestId,
            String model,
            String stopReason,
            long inputTokens,
            long outputTokens,
            long cacheCreationInputTokens,
            long cacheReadInputTokens) {
        USAGE.info(
                "sds_extraction request_id={} model={} stop_reason={} input_tokens={} "
                        + "output_tokens={} cache_creation_input_tokens={} cache_read_input_tokens={}",
                requestId,
                model,
                stopReason,
                inputTokens,
                outputTokens,
                cacheCreationInputTokens,
                cacheReadInputTokens);
    }

    public static void logAccess(
            String requestId, String method, String path, int statusCode, double durationMs) {
        ACCESS.info(
                "request request_id={} method={} path={} status={} duration_ms={}",
                requestId,
                method,
                path,
                statusCode,
                String.format(Locale.ROOT, "%.1f", durationMs));
    }
}
