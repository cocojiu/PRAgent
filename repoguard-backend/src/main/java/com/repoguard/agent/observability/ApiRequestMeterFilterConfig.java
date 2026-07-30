package com.repoguard.agent.observability;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApiRequestMeterFilterConfig {

    static final int MAX_API_PATHS = 200;

    private static final String API_REQUEST_DURATION_METER = "repoguard.api.request.duration";
    private static final String API_RESPONSE_BYTES_METER = "repoguard.api.response.bytes";

    @Bean
    public MeterFilter apiRequestDurationPathCardinalityLimit() {
        return maximumAllowableTags(API_REQUEST_DURATION_METER, "path", MAX_API_PATHS);
    }

    @Bean
    public MeterFilter apiResponseBytesPathCardinalityLimit() {
        return maximumAllowableTags(API_RESPONSE_BYTES_METER, "path", MAX_API_PATHS);
    }

    private MeterFilter maximumAllowableTags(String prefix, String tag, int maximumValues) {
        return MeterFilter.maximumAllowableTags(prefix, tag, maximumValues, MeterFilter.deny());
    }
}
