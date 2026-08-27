package com.repoguard.agent.tenancy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(TenantProperties.class)
public class TenantConfiguration {

    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilterRegistration(
        TenantProperties properties,
        TenantResolutionService resolutionService,
        ObjectMapper objectMapper
    ) {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TenantContextFilter(properties, resolutionService, objectMapper));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        return registration;
    }
}
