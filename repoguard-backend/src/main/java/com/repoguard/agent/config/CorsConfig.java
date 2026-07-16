package com.repoguard.agent.config;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(AppCorsProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private static final String WILDCARD_ORIGIN = "*";

    private final List<String> allowedOrigins;

    public CorsConfig(AppCorsProperties properties, Environment environment) {
        this.allowedOrigins = sanitizeAllowedOrigins(properties);
        if (this.allowedOrigins.contains(WILDCARD_ORIGIN)) {
            throw new IllegalStateException("app.cors.allowed-origins cannot contain * when credentials are allowed");
        }
        if (RuntimeProfilePolicy.isProductionLike(environment.getActiveProfiles()) && this.allowedOrigins.isEmpty()) {
            throw new IllegalStateException("app.cors.allowed-origins must be configured for a production-like profile");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins.toArray(String[]::new))
            .allowedMethods("*")
            .allowedHeaders("*")
            .exposedHeaders("X-Trace-Id", "X-Error-Id")
            .allowCredentials(true);
    }

    private List<String> sanitizeAllowedOrigins(AppCorsProperties properties) {
        if (properties.getAllowedOrigins() == null) {
            return List.of();
        }
        return properties.getAllowedOrigins().stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
    }
}
