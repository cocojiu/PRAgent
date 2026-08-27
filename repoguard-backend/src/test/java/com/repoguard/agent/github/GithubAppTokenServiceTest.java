package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubAppTokenServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signsAppJwtRequestsInstallationTokenAndCachesIt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = tokenServer(requests, authorization);
        OutboundEndpointPolicy endpointPolicy = mock(OutboundEndpointPolicy.class);
        GithubAppTokenService service = new GithubAppTokenService(
            properties(privateKeyPem(), Set.of(77L)),
            RestClient.builder(),
            endpointPolicy
        );
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        String first = service.installationToken(baseUrl, 77L);
        String second = service.installationToken(baseUrl, 77L);

        assertThat(first).isEqualTo("installation-token");
        assertThat(second).isEqualTo(first);
        assertThat(requests).hasValue(1);
        assertThat(authorization.get())
            .startsWith("Bearer ")
            .satisfies(header -> assertThat(header.substring("Bearer ".length()).split("\\."))
                .hasSize(3));
        verify(endpointPolicy, org.mockito.Mockito.times(2))
            .validate(OutboundEndpointType.GITHUB, baseUrl);
    }

    @Test
    void rejectsInstallationOutsideAllowlistBeforeNetworkCall() throws Exception {
        OutboundEndpointPolicy endpointPolicy = mock(OutboundEndpointPolicy.class);
        GithubAppTokenService service = new GithubAppTokenService(
            properties(privateKeyPem(), Set.of(77L)),
            RestClient.builder(),
            endpointPolicy
        );

        assertThatThrownBy(() -> service.installationToken("https://api.github.com", 88L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("allowlisted");
        verifyNoInteractions(endpointPolicy);
    }

    @Test
    void rejectsDisabledOrIncompleteAppConfiguration() throws Exception {
        GithubAppProperties properties = properties(privateKeyPem(), Set.of());
        properties.setEnabled(false);
        OutboundEndpointPolicy endpointPolicy = mock(OutboundEndpointPolicy.class);
        GithubAppTokenService service = new GithubAppTokenService(
            properties,
            RestClient.builder(),
            endpointPolicy
        );

        assertThat(service.isEnabled()).isFalse();
        assertThatThrownBy(() -> service.installationToken("https://api.github.com", 77L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fully configured");
        verifyNoInteractions(endpointPolicy);
    }

    private HttpServer tokenServer(
        AtomicInteger requests,
        AtomicReference<String> authorization
    ) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/app/installations/77/access_tokens", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = ("{\"token\":\"installation-token\",\"expires_at\":\""
                + Instant.now().plusSeconds(3600)
                + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        value.start();
        return value;
    }

    private GithubAppProperties properties(String privateKey, Set<Long> installations) {
        GithubAppProperties value = new GithubAppProperties();
        value.setEnabled(true);
        value.setAppId(12345L);
        value.setPrivateKey(privateKey);
        value.setAllowedInstallationIds(installations);
        return value;
    }

    private String privateKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----";
    }
}
