package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.Ordered;

class SecurityHeadersFilterConfigTest {

    @Test
    void registersSecurityHeadersFilterForAllPathsAtHighestPrecedence() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SecurityHeadersFilterConfig.class);
            context.refresh();

            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);

            assertThat(registration.getFilter()).isInstanceOf(SecurityHeadersFilter.class);
            assertThat(registration.getUrlPatterns()).containsExactly("/*");
            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        }
    }
}
