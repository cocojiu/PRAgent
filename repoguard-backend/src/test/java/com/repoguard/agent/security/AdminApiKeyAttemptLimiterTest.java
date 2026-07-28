package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.TrustedProxyClientIpResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminApiKeyAttemptLimiterTest {

    @Test
    void enforcesFailureLimitPerClientAndFailsClosedAtTrackingCapacity() {
        AdminApiKeyProperties properties = new AdminApiKeyProperties();
        properties.setFailedRequestsPerMinutePerIp(2);
        properties.setMaxTrackedClients(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TrustedProxyClientIpResolver resolver = mock(TrustedProxyClientIpResolver.class);
        when(resolver.resolve(any())).thenAnswer(invocation ->
            ((MockHttpServletRequest) invocation.getArgument(0)).getRemoteAddr()
        );
        AdminApiKeyAttemptLimiter limiter = new AdminApiKeyAttemptLimiter(
            properties,
            registry,
            resolver,
            Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC)
        );

        assertThat(limiter.recordFailureAllowed(request("192.0.2.1"))).isTrue();
        assertThat(limiter.recordFailureAllowed(request("192.0.2.1"))).isTrue();
        assertThat(limiter.recordFailureAllowed(request("192.0.2.1"))).isFalse();
        assertThat(limiter.recordFailureAllowed(request("192.0.2.2"))).isTrue();
        assertThat(limiter.recordFailureAllowed(request("192.0.2.3"))).isFalse();

        assertThat(registry.counter("repoguard.security.admin_api_key.rate_limited").count()).isEqualTo(1.0d);
        assertThat(
            registry.counter("repoguard.security.admin_api_key.rate_limiter_saturated").count()
        ).isEqualTo(1.0d);
    }

    private MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
