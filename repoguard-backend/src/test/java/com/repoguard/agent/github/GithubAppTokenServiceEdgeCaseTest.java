package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubAppTokenServiceEdgeCaseTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void acceptsPkcs1PrivateKeyAndNormalizesTrailingSlashes() throws Exception {
        server = tokenServer(successResponse(Instant.now().plusSeconds(3600)));
        GithubAppTokenService service = service(pkcs1PrivateKeyPem(), Set.of());
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "///";

        assertThat(service.installationToken(baseUrl, 77L)).isEqualTo("installation-token");
    }

    @Test
    void rejectsResponseWithoutToken() throws Exception {
        server = tokenServer("{\"token\":\"\",\"expires_at\":\"2030-01-01T00:00:00Z\"}");
        GithubAppTokenService service = service(pkcs8PrivateKeyPem(), Set.of(77L));

        assertThatThrownBy(() -> service.installationToken(baseUrl(), 77L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("did not contain a token");
    }

    @Test
    void rejectsInvalidOrPrematureExpiry() throws Exception {
        server = tokenServer("{\"token\":\"installation-token\",\"expires_at\":\"invalid\"}");
        GithubAppTokenService invalidExpiryService = service(pkcs8PrivateKeyPem(), Set.of(77L));

        assertThatThrownBy(() -> invalidExpiryService.installationToken(baseUrl(), 77L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalid expiry");

        server.stop(0);
        server = tokenServer(successResponse(Instant.now().plusSeconds(5)));
        GithubAppTokenService prematureExpiryService = service(pkcs8PrivateKeyPem(), Set.of(77L));

        assertThatThrownBy(() -> prematureExpiryService.installationToken(baseUrl(), 77L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expires too soon");
    }

    @Test
    void rejectsMissingInstallationIdBeforeEndpointValidation() throws Exception {
        OutboundEndpointPolicy endpointPolicy = mock(OutboundEndpointPolicy.class);
        GithubAppTokenService service = new GithubAppTokenService(
            properties(pkcs8PrivateKeyPem(), Set.of()),
            RestClient.builder(),
            endpointPolicy
        );

        assertThatThrownBy(() -> service.installationToken("https://api.github.com", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("installation id");
        assertThatThrownBy(() -> service.installationToken("https://api.github.com", 0L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("installation id");
        verifyNoInteractions(endpointPolicy);
    }

    @Test
    void rejectsMissingOrInvalidPrivateKey() {
        assertInvalidPrivateKey(" ", "private key is required");
        assertInvalidPrivateKey("not-a-pem", "not valid RSA PEM");
    }

    private void assertInvalidPrivateKey(String privateKey, String expectedMessage) {
        GithubAppTokenService service = service(privateKey, Set.of(77L));

        assertThatThrownBy(() -> service.installationToken("http://127.0.0.1:1", 77L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(expectedMessage);
    }

    private GithubAppTokenService service(String privateKey, Set<Long> installations) {
        return new GithubAppTokenService(
            properties(privateKey, installations),
            RestClient.builder(),
            mock(OutboundEndpointPolicy.class)
        );
    }

    private GithubAppProperties properties(String privateKey, Set<Long> installations) {
        GithubAppProperties value = new GithubAppProperties();
        value.setEnabled(true);
        value.setAppId(12345L);
        value.setPrivateKey(privateKey);
        value.setAllowedInstallationIds(installations);
        return value;
    }

    private HttpServer tokenServer(String body) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/app/installations/77/access_tokens", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        value.start();
        return value;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String successResponse(Instant expiry) {
        return "{\"token\":\"installation-token\",\"expires_at\":\"" + expiry + "\"}";
    }

    private String pkcs8PrivateKeyPem() throws Exception {
        KeyPair pair = rsaKeyPair();
        return pem("PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    private String pkcs1PrivateKeyPem() throws Exception {
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) rsaKeyPair().getPrivate();
        byte[] body = sequence(
            integer(BigInteger.ZERO),
            integer(key.getModulus()),
            integer(key.getPublicExponent()),
            integer(key.getPrivateExponent()),
            integer(key.getPrimeP()),
            integer(key.getPrimeQ()),
            integer(key.getPrimeExponentP()),
            integer(key.getPrimeExponentQ()),
            integer(key.getCrtCoefficient())
        );
        return pem("RSA PRIVATE KEY", body);
    }

    private KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    private String pem(String type, byte[] content) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(content);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----";
    }

    private byte[] integer(BigInteger value) {
        return der(0x02, value.toByteArray());
    }

    private byte[] sequence(byte[]... values) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] value : values) {
            content.writeBytes(value);
        }
        return der(0x30, content.toByteArray());
    }

    private byte[] der(int tag, byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(tag);
        writeLength(output, value.length);
        output.writeBytes(value);
        return output.toByteArray();
    }

    private void writeLength(ByteArrayOutputStream output, int length) {
        if (length < 128) {
            output.write(length);
            return;
        }
        int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
        output.write(0x80 | bytes);
        for (int index = bytes - 1; index >= 0; index--) {
            output.write((length >>> (index * 8)) & 0xff);
        }
    }
}
