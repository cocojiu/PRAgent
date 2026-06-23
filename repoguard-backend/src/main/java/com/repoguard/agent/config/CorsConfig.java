package com.repoguard.agent.config;

import java.util.Arrays;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(AppCorsProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private final AppCorsProperties properties;

    public CorsConfig(AppCorsProperties properties, Environment environment) {
        this.properties = properties;
        if (isProd(environment) && hasNoAllowedOrigins(properties)) {
            throw new IllegalStateException("app.cors.allowed-origins must be configured for prod profile");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(properties.getAllowedOrigins().toArray(String[]::new))
            .allowedMethods("*")
            .allowedHeaders("*");
    }

    private boolean isProd(Environment environment) {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }

    private boolean hasNoAllowedOrigins(AppCorsProperties properties) {
        return properties.getAllowedOrigins() == null
            || properties.getAllowedOrigins().stream().noneMatch(StringUtils::hasText);
    }
}
