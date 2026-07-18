package jp.co.ajs.afcsds.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import jp.co.ajs.afcsds.core.AppLogs;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assign a request_id to every request and log method/path/status/latency.
 *
 * <p>The same request_id is passed down to the extraction usage log and
 * returned in the X-Request-ID response header (and in error bodies), so a
 * client-reported failure can be correlated with server logs. A caller may
 * supply its own correlation id in the X-Request-ID request header; it is
 * honored when it looks like a safe id (so cross-system log correlation
 * works end to end) and replaced with a fresh UUID otherwise — arbitrary
 * header text must not flow into logs. The id is also published to the SLF4J
 * MDC as {@code request_id} for the JSON log format.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = "afcSds.requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String MDC_KEY = "request_id";

    // Client-supplied ids are accepted only in this shape; anything else
    // (control characters, log-injection attempts, unbounded length) is
    // discarded in favor of a generated UUID.
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        // Set before delegating so the header survives even if the response is
        // committed by an error path deeper in the stack.
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_KEY, requestId);

        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            double durationMs = (System.nanoTime() - start) / 1_000_000.0;
            AppLogs.logAccess(
                    requestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs);
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolveRequestId(String incoming) {
        if (incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    /** The request_id assigned by this filter, or {@code null} outside a request. */
    public static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
