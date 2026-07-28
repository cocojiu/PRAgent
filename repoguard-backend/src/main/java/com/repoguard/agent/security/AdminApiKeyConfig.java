package com.repoguard.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(AdminApiKeyProperties.class)
public class AdminApiKeyConfig {

    @Bean
    public FilterRegistrationBean<AdminApiKeyFilter> adminApiKeyFilterRegistration(
        AdminApiKeyProperties properties,
        AuthTokenService authTokenService,
        ObjectMapper objectMapper,
        AdminApiKeyAttemptLimiter attemptLimiter,
        AdminApiKeyFailureAuditRecorder failureAuditRecorder,
        Environment environment
    ) {
        properties.validateForProfiles(environment.getActiveProfiles());
        FilterRegistrationBean<AdminApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(
            new AdminApiKeyFilter(
                properties,
                authTokenService,
                objectMapper,
                attemptLimiter,
                failureAuditRecorder
            )
        );
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return registration;
    }
}
