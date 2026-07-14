package com.repoguard.agent.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class AdminOperationAuditWebConfig implements WebMvcConfigurer {

    private final AdminOperationAuditInterceptor interceptor;

    public AdminOperationAuditWebConfig(AdminOperationAuditInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
            .addPathPatterns(
                "/api/v1/config/**",
                "/api/v1/notification-events/**",
                "/api/v1/message-queue/**"
            )
            .excludePathPatterns("/api/v1/config/data-retention/cleanup-audits");
    }
}
