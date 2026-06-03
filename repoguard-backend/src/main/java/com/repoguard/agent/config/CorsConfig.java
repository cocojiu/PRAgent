package com.repoguard.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(AppCorsProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private final AppCorsProperties properties;

    public CorsConfig(AppCorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(properties.getAllowedOrigins().toArray(String[]::new))
            .allowedMethods("*")
            .allowedHeaders("*");
    }
}
