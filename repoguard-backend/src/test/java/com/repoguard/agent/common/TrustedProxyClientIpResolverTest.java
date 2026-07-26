package com.repoguard.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedProxyClientIpResolverTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TrustedProxyClientIpResolver resolver =
        new TrustedProxyClientIpResolver(new TrustedProxyProperties(), registry);

    @Test
    void ignoresForwardedHeadersFromUntrustedPeer() {
        MockHttpServletRequest request = request("203.0.113.50");
        request.addHeader("X-Real-IP", "198.51.100.7");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 198.51.100.8");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.50");
        assertThat(ignoredCount("untrusted_proxy")).isEqualTo(1.0);
    }

    @Test
    void trustsRealIpFromLoopbackAndPrivateDockerNetworks() {
        for (String peer : List.of("127.0.0.1", "::1", "10.4.5.6", "172.18.0.2", "192.168.1.9", "::ffff:172.18.0.2")) {
            MockHttpServletRequest request = request(peer);
            request.addHeader("X-Real-IP", "203.0.113.9");

            assertThat(resolver.resolve(request)).as("peer %s", peer).isEqualTo("203.0.113.9");
        }
    }

    @Test
    void doesNotTrustPublicPeersJustOutsideThePrivateRanges() {
        for (String peer : List.of("172.15.255.255", "172.32.0.1", "11.0.0.1", "9.255.255.255", "193.168.1.9")) {
            MockHttpServletRequest request = request(peer);
            request.addHeader("X-Real-IP", "203.0.113.9");

            assertThat(resolver.resolve(request)).as("peer %s", peer).isEqualTo(peer);
        }
    }

    @Test
    void returnsPeerWhenTrustedProxySendsNoForwardedHeader() {
        assertThat(resolver.resolve(request("172.18.0.2"))).isEqualTo("172.18.0.2");
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void fallsBackToRightmostForwardedForEntryWhenRealIpIsAbsent() {
        MockHttpServletRequest request = request("172.18.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 203.0.113.9");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void usesRightmostEntryWhenRealIpContainsMultipleAddresses() {
        MockHttpServletRequest request = request("172.18.0.2");
        request.addHeader("X-Real-IP", "198.51.100.7, 203.0.113.9");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void canonicalizesForwardedIpv6Address() {
        MockHttpServletRequest request = request("172.18.0.2");
        request.addHeader("X-Real-IP", "2001:DB8::1");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void fallsBackToPeerForMalformedForwardedValues() {
        List<String> malformed = List.of(
            "not-an-ip",
            "203.0.113.9; DROP TABLE users",
            "203.0.113.9 203.0.113.10",
            "203.0.113.9:8080",
            "203.0.113.9%25",
            "a".repeat(46),
            "1.1.1.1,".repeat(200),
            "203.0.113.9, evil"
        );
        for (String value : malformed) {
            MockHttpServletRequest request = request("172.18.0.2");
            request.addHeader("X-Real-IP", value);

            assertThat(resolver.resolve(request)).as("header %s", value).isEqualTo("172.18.0.2");
        }
        assertThat(ignoredCount("malformed_header")).isEqualTo(malformed.size());
    }

    @Test
    void fallsBackToPeerForAbbreviatedForwardedValues() {
        List<String> abbreviated = List.of(
            "172.16",
            "10",
            "0177.0.0.1",
            "172.016.0.0",
            "203.0.113.9.1",
            "256.0.113.9",
            "::ffff:172.16"
        );
        for (String value : abbreviated) {
            MockHttpServletRequest request = request("172.18.0.2");
            request.addHeader("X-Real-IP", value);

            assertThat(resolver.resolve(request)).as("header %s", value).isEqualTo("172.18.0.2");
        }
        assertThat(ignoredCount("malformed_header")).isEqualTo(abbreviated.size());
    }

    @Test
    void ignoresBlankRealIpAndUsesForwardedForFallback() {
        MockHttpServletRequest request = request("172.18.0.2");
        request.addHeader("X-Real-IP", "   ");
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void honorsConfiguredNetworksInsteadOfDefaults() {
        TrustedProxyProperties properties = new TrustedProxyProperties();
        properties.setNetworks(List.of("198.51.100.0/24"));
        TrustedProxyClientIpResolver customResolver = new TrustedProxyClientIpResolver(properties, registry);

        MockHttpServletRequest trusted = request("198.51.100.20");
        trusted.addHeader("X-Real-IP", "203.0.113.9");
        MockHttpServletRequest untrusted = request("127.0.0.1");
        untrusted.addHeader("X-Real-IP", "203.0.113.9");

        assertThat(customResolver.resolve(trusted)).isEqualTo("203.0.113.9");
        assertThat(customResolver.resolve(untrusted)).isEqualTo("127.0.0.1");
    }

    @Test
    void rejectsInvalidNetworkConfigurationAtStartup() {
        TrustedProxyProperties invalidNetwork = new TrustedProxyProperties();
        invalidNetwork.setNetworks(List.of("not-a-network/24"));
        TrustedProxyProperties invalidPrefix = new TrustedProxyProperties();
        invalidPrefix.setNetworks(List.of("10.0.0.0/33"));

        assertThatThrownBy(() -> new TrustedProxyClientIpResolver(invalidNetwork, registry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not-a-network/24");
        assertThatThrownBy(() -> new TrustedProxyClientIpResolver(invalidPrefix, registry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("10.0.0.0/33");
    }

    @Test
    void rejectsAbbreviatedNetworkConfigurationAtStartup() {
        for (String network : List.of("172.16/12", "10/8", "0177.0.0.1/32", "172.016.0.0/12", "::ffff:172.16/108")) {
            TrustedProxyProperties properties = new TrustedProxyProperties();
            properties.setNetworks(List.of(network));

            assertThatThrownBy(() -> new TrustedProxyClientIpResolver(properties, registry))
                .as("network %s", network)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(network);
        }
    }

    @Test
    void acceptsFullCidrNetworksIncludingIpv6() {
        TrustedProxyProperties properties = new TrustedProxyProperties();
        properties.setNetworks(List.of("172.16.0.0/12", "2001:db8::/32"));
        TrustedProxyClientIpResolver customResolver = new TrustedProxyClientIpResolver(properties, registry);

        for (String peer : List.of("172.31.255.254", "::ffff:172.18.0.2", "2001:db8:0:0:0:0:0:5")) {
            MockHttpServletRequest request = request(peer);
            request.addHeader("X-Real-IP", "203.0.113.9");

            assertThat(customResolver.resolve(request)).as("peer %s", peer).isEqualTo("203.0.113.9");
        }
        assertThat(customResolver.resolve(request("172.15.255.255"))).isEqualTo("172.15.255.255");
    }

    private MockHttpServletRequest request(String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(peer);
        return request;
    }

    private double ignoredCount(String reason) {
        return registry.counter("repoguard.security.forwarded_client_ip_ignored", "reason", reason).count();
    }
}
