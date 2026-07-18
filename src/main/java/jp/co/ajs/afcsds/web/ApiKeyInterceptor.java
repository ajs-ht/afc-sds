package jp.co.ajs.afcsds.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import jp.co.ajs.afcsds.config.AppSettings;
import jp.co.ajs.afcsds.core.AppExceptions.UnauthorizedException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * X-API-Key shared-secret auth for every {@code /v1/**} route.
 *
 * <p>Rejects the request unless X-API-Key matches one of the configured
 * secrets, via constant-time compare. API_KEY may list several
 * comma-separated keys so a rotation can accept old and new keys
 * simultaneously; every configured key is always compared (no early exit)
 * to keep timing independent of which key matched.
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final List<byte[]> apiKeyBytes;

    public ApiKeyInterceptor(AppSettings settings) {
        this.apiKeyBytes =
                settings.apiKeys().stream()
                        .map(key -> key.getBytes(StandardCharsets.UTF_8))
                        .toList();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null) {
            throw new UnauthorizedException("Invalid or missing X-API-Key header.");
        }
        byte[] candidate = apiKey.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (byte[] expected : apiKeyBytes) {
            matched |= MessageDigest.isEqual(candidate, expected);
        }
        if (!matched) {
            throw new UnauthorizedException("Invalid or missing X-API-Key header.");
        }
        return true;
    }
}
