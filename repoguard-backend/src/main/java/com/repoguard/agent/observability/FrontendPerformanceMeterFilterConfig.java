package com.repoguard.agent.observability;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FrontendPerformanceMeterFilterConfig {

    static final int MAX_FRONTEND_ROUTES = 64;
    static final int MAX_FRONTEND_OPERATIONS = 256;
    static final int MAX_FRONTEND_PATHS = 512;
    static final int MAX_THRESHOLD_SUBJECTS = 1024;

    private static final String FRONTEND_METER_PREFIX = "repoguard.frontend.";
    private static final String THRESHOLD_METER_PREFIX = "repoguard.observability.threshold.exceeded";

    @Bean
    public MeterFilter frontendRouteCardinalityLimit() {
        return maximumAllowableTags(FRONTEND_METER_PREFIX, "route", MAX_FRONTEND_ROUTES);
    }

    @Bean
    public MeterFilter frontendOperationCardinalityLimit() {
        return maximumAllowableTags(FRONTEND_METER_PREFIX, "operation", MAX_FRONTEND_OPERATIONS);
    }

    @Bean
    public MeterFilter frontendPathCardinalityLimit() {
        return maximumAllowableTags(FRONTEND_METER_PREFIX, "path", MAX_FRONTEND_PATHS);
    }

    @Bean
    public MeterFilter thresholdSubjectCardinalityLimit() {
        return maximumAllowableTags(THRESHOLD_METER_PREFIX, "subject", MAX_THRESHOLD_SUBJECTS);
    }

    private MeterFilter maximumAllowableTags(String prefix, String tag, int maximumValues) {
        return MeterFilter.maximumAllowableTags(prefix, tag, maximumValues, MeterFilter.deny());
    }
}
