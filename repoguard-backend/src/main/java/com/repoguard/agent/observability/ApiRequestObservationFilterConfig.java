package com.repoguard.agent.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class ApiRequestObservationFilterConfig {

    @Bean
    public FilterRegistrationBean<ApiRequestObservationFilter> apiRequestObservationFilterRegistration(
        RepoGuardMetrics metrics
    ) {
        FilterRegistrationBean<ApiRequestObservationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiRequestObservationFilter(metrics));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
