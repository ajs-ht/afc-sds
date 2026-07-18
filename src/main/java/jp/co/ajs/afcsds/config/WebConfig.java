package jp.co.ajs.afcsds.config;

import jakarta.servlet.MultipartConfigElement;
import jp.co.ajs.afcsds.web.ApiKeyInterceptor;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiKeyInterceptor apiKeyInterceptor;

    public WebConfig(ApiKeyInterceptor apiKeyInterceptor) {
        this.apiKeyInterceptor = apiKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // X-API-Key auth applies router-wide to /v1/**; /healthz stays open.
        registry.addInterceptor(apiKeyInterceptor).addPathPatterns("/v1/**");
    }

    /**
     * Servlet-level multipart cap: MAX_UPLOAD_MB plus a little slack for the
     * multipart framing, so our own validation (with its canonical
     * file_too_large error body) is what callers normally see. Anything so
     * large it trips this cap instead is mapped to the same error type in
     * GlobalExceptionHandler.
     */
    @Bean
    public MultipartConfigElement multipartConfigElement(AppSettings settings) {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(settings.maxUploadBytes() + DataSize.ofMegabytes(1).toBytes()));
        factory.setMaxRequestSize(
                DataSize.ofBytes(settings.maxUploadBytes() + DataSize.ofMegabytes(2).toBytes()));
        return factory.createMultipartConfig();
    }
}
