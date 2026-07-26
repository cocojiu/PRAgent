package com.repoguard.agent.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.common.TrustedProxyClientIpResolver;
import com.repoguard.agent.common.TrustedProxyProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AuditClientIpResolverTest {

    private final AuditClientIpResolver resolver = new AuditClientIpResolver(
        new TrustedProxyClientIpResolver(new TrustedProxyProperties(), new SimpleMeterRegistry())
    );

    @Test
    void usesForwardedClientIpFromTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.2");
        request.addHeader("X-Real-IP", "203.0.113.9");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void ignoresForwardedClientIpFromUntrustedPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.50");
        request.addHeader("X-Real-IP", "198.51.100.7");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.50");
    }

    @Test
    void returnsNullForMissingRequest() {
        assertThat(resolver.resolve(null)).isNull();
    }

    @Test
    void truncatesOverlongPeerAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("a".repeat(80));

        assertThat(resolver.resolve(request)).isEqualTo("a".repeat(64));
    }
}
