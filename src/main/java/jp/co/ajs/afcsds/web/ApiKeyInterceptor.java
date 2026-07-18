package jp.co.ajs.afcsds.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jp.co.ajs.afcsds.config.AppSettings;
import jp.co.ajs.afcsds.core.AppExceptions.UnauthorizedException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * X-API-Key shared-secret auth for every {@code /v1/**} route.
 *
 * <p>Rejects the request unless X-API-Key matches, via constant-time compare.
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final AppSettings settings;

    public ApiKeyInterceptor(AppSettings settings) {
        this.settings = settings;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null
                || !MessageDigest.isEqual(
                        apiKey.getBytes(StandardCharsets.UTF_8),
                        settings.apiKey().getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Invalid or missing X-API-Key header.");
        }
        return true;
    }
}
