package jp.co.ajs.afcsds.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import jp.co.ajs.afcsds.core.AppLogs;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assign a request_id to every request and log method/path/status/latency.
 *
 * <p>The same request_id is passed down to the extraction usage log and
 * returned in the X-Request-ID response header (and in error bodies), so a
 * client-reported failure can be correlated with server logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_ATTRIBUTE = "afcSds.requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        // Set before delegating so the header survives even if the response is
        // committed by an error path deeper in the stack.
        response.setHeader(REQUEST_ID_HEADER, requestId);

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
        }
    }

    /** The request_id assigned by this filter, or {@code null} outside a request. */
    public static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
