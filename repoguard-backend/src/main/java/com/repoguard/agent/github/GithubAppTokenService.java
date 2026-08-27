package com.repoguard.agent.github;

import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class GithubAppTokenService {

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final String PEM_BOUNDARY = "-----";

    private final GithubAppProperties properties;
    private final RestClient restClient;
    private final OutboundEndpointPolicy endpointPolicy;
    private final ConcurrentMap<Long, CachedToken> tokens = new ConcurrentHashMap<>();
    private volatile PrivateKey privateKey;

    public GithubAppTokenService(
        GithubAppProperties properties,
        RestClient.Builder restClientBuilder,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.restClient = GithubRestClientFactory.build(Objects.requireNonNull(restClientBuilder, "restClientBuilder"));
        this.endpointPolicy = Objects.requireNonNull(endpointPolicy, "endpointPolicy");
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String installationToken(String baseUrl, Long installationId) {
        requireConfiguredInstallation(installationId);
        endpointPolicy.validate(OutboundEndpointType.GITHUB, baseUrl);
        Instant now = Instant.now();
        CachedToken cached = tokens.get(installationId);
        if (cached != null && now.isBefore(cached.refreshAt())) {
            return cached.token();
        }
        return tokens.compute(installationId, (id, current) -> {
            Instant refreshedNow = Instant.now();
            if (current != null && refreshedNow.isBefore(current.refreshAt())) {
                return current;
            }
            return requestInstallationToken(baseUrl, id, refreshedNow);
        }).token();
    }

    private CachedToken requestInstallationToken(String baseUrl, Long installationId, Instant now) {
        String endpoint = stripTrailingSlash(baseUrl) + "/app/installations/" + installationId + "/access_tokens";
        InstallationTokenResponse response = restClient.post()
            .uri(endpoint)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.parseMediaType("application/vnd.github+json"))
            .header("Authorization", "Bearer " + appJwt(now))
            .header("X-GitHub-Api-Version", properties.getApiVersion())
            .body("{}")
            .retrieve()
            .body(InstallationTokenResponse.class);
        if (response == null || !StringUtils.hasText(response.token())) {
            throw new IllegalStateException("GitHub App installation token response did not contain a token");
        }
        String token = response.token();
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(response.expires_at());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("GitHub App installation token response had an invalid expiry", exception);
        }
        int skew = Math.max(30, properties.getTokenRefreshSkewSeconds());
        Instant refreshAt = expiresAt.minusSeconds(skew);
        if (!refreshAt.isAfter(now)) {
            throw new IllegalStateException("GitHub App installation token expires too soon");
        }
        return new CachedToken(token, refreshAt);
    }

    private String appJwt(Instant now) {
        requireConfiguredApp();
        long issuedAt = now.minusSeconds(60).getEpochSecond();
        long expiresAt = now.plusSeconds(8 * 60L).getEpochSecond();
        String header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = encode(
            "{\"iat\":" + issuedAt + ",\"exp\":" + expiresAt + ",\"iss\":\"" + properties.getAppId() + "\"}"
        );
        String signingInput = header + "." + payload;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + BASE64_URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("GitHub App JWT could not be signed", exception);
        }
    }

    private PrivateKey privateKey() {
        PrivateKey current = privateKey;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (privateKey == null) {
                privateKey = parsePrivateKey(properties.getPrivateKey());
            }
            return privateKey;
        }
    }

    private PrivateKey parsePrivateKey(String pem) {
        if (!StringUtils.hasText(pem)) {
            throw new IllegalStateException("GitHub App private key is required");
        }
        String normalized = pem.replace("\\n", "\n").trim();
        boolean pkcs1 = normalized.contains("BEGIN RSA PRIVATE KEY");
        String body = normalized
            .replace(pemBoundary("BEGIN PRIVATE KEY"), "")
            .replace(pemBoundary("END PRIVATE KEY"), "")
            .replace(pemBoundary("BEGIN RSA PRIVATE KEY"), "")
            .replace(pemBoundary("END RSA PRIVATE KEY"), "")
            .replaceAll("\\s", "");
        try {
            byte[] keyBytes = Base64.getDecoder().decode(body);
            byte[] pkcs8 = pkcs1 ? wrapPkcs1(keyBytes) : keyBytes;
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new IllegalStateException("GitHub App private key is not valid RSA PEM", exception);
        }
    }

    private String pemBoundary(String label) {
        return PEM_BOUNDARY + label + PEM_BOUNDARY;
    }

    private byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] rsaAlgorithm = {
            0x30, 0x0d, 0x06, 0x09,
            0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
        };
        byte[] privateKeyOctets = der(0x04, pkcs1);
        ByteBuffer content = ByteBuffer.allocate(version.length + rsaAlgorithm.length + privateKeyOctets.length);
        content.put(version).put(rsaAlgorithm).put(privateKeyOctets);
        return der(0x30, content.array());
    }

    private byte[] der(int tag, byte[] value) {
        byte[] length = derLength(value.length);
        ByteBuffer buffer = ByteBuffer.allocate(1 + length.length + value.length);
        buffer.put((byte) tag).put(length).put(value);
        return buffer.array();
    }

    private byte[] derLength(int length) {
        if (length < 128) {
            return new byte[] {(byte) length};
        }
        int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
        byte[] encoded = new byte[bytes + 1];
        encoded[0] = (byte) (0x80 | bytes);
        for (int index = bytes; index > 0; index--) {
            encoded[index] = (byte) (length & 0xff);
            length >>>= 8;
        }
        return encoded;
    }

    private void requireConfiguredApp() {
        if (!properties.isEnabled() || properties.getAppId() == null || properties.getAppId() < 1) {
            throw new IllegalStateException("GitHub App is not fully configured");
        }
    }

    private void requireConfiguredInstallation(Long installationId) {
        requireConfiguredApp();
        if (installationId == null || installationId < 1) {
            throw new IllegalStateException("GitHub App installation id is required");
        }
        if (!properties.getAllowedInstallationIds().isEmpty()
            && !properties.getAllowedInstallationIds().contains(installationId)) {
            throw new IllegalStateException("GitHub App installation id is not allowlisted");
        }
    }

    private String encode(String value) {
        return BASE64_URL.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String stripTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "https://api.github.com";
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record InstallationTokenResponse(String token, String expires_at) {
    }

    private record CachedToken(String token, Instant refreshAt) {
    }
}
