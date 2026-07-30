package com.repoguard.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AuthTokenFilterConfig {

    @Bean
    public FilterRegistrationBean<AuthTokenFilter> authTokenFilterRegistration(
        AuthTokenService authTokenService,
        AuthAccountCache authAccountCache,
        ObjectMapper objectMapper
    ) {
        FilterRegistrationBean<AuthTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AuthTokenFilter(authTokenService, authAccountCache, objectMapper));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
