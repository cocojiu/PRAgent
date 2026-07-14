package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboundEndpointPolicyTest {

    private static final String ENDPOINT = "https://api.example.test/v1";

    @Test
    void allowsHostsResolvingOnlyToPublicIpv4OrIpv6Addresses() throws Exception {
        OutboundEndpointPolicy ipv4Policy = policy(InetAddress.getByName("8.8.8.8"));
        OutboundEndpointPolicy ipv6Policy = policy(InetAddress.getByName("2606:4700:4700::1111"));

        assertThat(ipv4Policy.validate(OutboundEndpointType.GITHUB, ENDPOINT)).isEqualTo(URI.create(ENDPOINT));
        assertThat(ipv6Policy.validate(OutboundEndpointType.GITHUB, ENDPOINT)).isEqualTo(URI.create(ENDPOINT));
    }

    @Test
    void rejectsPrivateIpv4Ipv6AndIpv4MappedIpv6Addresses() throws Exception {
        InetAddress privateIpv4 = InetAddress.getByName("10.20.30.40");
        InetAddress privateIpv6 = InetAddress.getByName("fd00::1");
        byte[] mappedBytes = new byte[16];
        mappedBytes[10] = (byte) 0xff;
        mappedBytes[11] = (byte) 0xff;
        mappedBytes[12] = 10;
        mappedBytes[15] = 1;
        InetAddress mappedIpv4 = Inet6Address.getByAddress(null, mappedBytes, -1);

        assertRejectedAsNonPublic(policy(privateIpv4));
        assertRejectedAsNonPublic(policy(privateIpv6));
        assertRejectedAsNonPublic(policy(mappedIpv4));
    }

    @Test
    void rejectsMixedPublicAndPrivateDnsAnswers() throws Exception {
        OutboundEndpointPolicy policy = policy(
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("192.168.10.5")
        );

        assertRejectedAsNonPublic(policy);
    }

    @Test
    void allowsPrivateAddressesOnlyForExplicitlyConfiguredHost() throws Exception {
        OutboundEndpointProperties properties = properties();
        properties.setPrivateNetworkAllowedHosts(List.of("api.example.test"));
        OutboundEndpointPolicy policy = new OutboundEndpointPolicy(
            properties,
            ignored -> new InetAddress[] { InetAddress.getByName("10.20.30.40") }
        );

        assertThat(policy.validate(OutboundEndpointType.GITHUB, ENDPOINT)).isEqualTo(URI.create(ENDPOINT));
    }

    @Test
    void rejectsDisallowedSchemeHostAndPortBeforeConnecting() throws Exception {
        OutboundEndpointPolicy policy = policy(InetAddress.getByName("8.8.8.8"));

        assertThatThrownBy(() -> policy.validate(OutboundEndpointType.GITHUB, "http://api.example.test/v1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scheme is not allowed");
        assertThatThrownBy(() -> policy.validate(OutboundEndpointType.GITHUB, "https://other.example.test/v1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("host is not allowlisted");
        assertThatThrownBy(() -> policy.validate(OutboundEndpointType.GITHUB, "https://api.example.test:8443/v1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("port is not allowlisted");
    }

    @Test
    void sameOriginNormalizesCaseTrailingDotAndDefaultPort() {
        OutboundEndpointPolicy policy = policy();

        assertThat(policy.sameOrigin(
            OutboundEndpointType.GITHUB,
            "HTTPS://API.EXAMPLE.TEST./v1/pulls",
            "https://api.example.test:443/v2/issues"
        )).isTrue();
        assertThat(policy.sameOrigin(
            OutboundEndpointType.GITHUB,
            "https://api.example.test/v1",
            "https://other.example.test/v1"
        )).isFalse();
        assertThat(policy.sameOrigin(
            OutboundEndpointType.GITHUB,
            "https://api.example.test/v1",
            "https://api.example.test:444/v1"
        )).isFalse();
    }

    private void assertRejectedAsNonPublic(OutboundEndpointPolicy policy) {
        assertThatThrownBy(() -> policy.validate(OutboundEndpointType.GITHUB, ENDPOINT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("resolved address is not public");
    }

    private OutboundEndpointPolicy policy(InetAddress... addresses) {
        return new OutboundEndpointPolicy(properties(), ignored -> addresses);
    }

    private OutboundEndpointProperties properties() {
        OutboundEndpointProperties properties = new OutboundEndpointProperties();
        properties.setGithubAllowedHosts(List.of("api.example.test"));
        properties.setPrivateNetworkAllowedHosts(List.of());
        return properties;
    }
}
