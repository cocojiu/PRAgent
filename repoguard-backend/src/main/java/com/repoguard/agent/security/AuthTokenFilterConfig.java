package com.repoguard.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.authentication.EnterpriseOidcAuthenticator;
import org.springframework.beans.factory.ObjectProvider;
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
        ObjectMapper objectMapper,
        ObjectProvider<EnterpriseOidcAuthenticator> enterpriseOidcAuthenticatorProvider
    ) {
        FilterRegistrationBean<AuthTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AuthTokenFilter(
            authTokenService,
            authAccountCache,
            objectMapper,
            enterpriseOidcAuthenticatorProvider.getIfAvailable()
        ));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
