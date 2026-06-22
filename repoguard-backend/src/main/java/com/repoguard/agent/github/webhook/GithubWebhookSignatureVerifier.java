package com.repoguard.agent.github.webhook;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GithubWebhookSignatureVerifier {

    private static final String SHA256_PREFIX = "sha256=";
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final GithubWebhookProperties properties;

    public GithubWebhookSignatureVerifier(GithubWebhookProperties properties) {
        this.properties = properties;
    }

    public void verify(String signatureHeader, byte[] payload) {
        if (!properties.isRequireSignature()) {
            return;
        }
        if (!StringUtils.hasText(properties.getSecret())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "GitHub webhook secret is not configured");
        }
        if (!StringUtils.hasText(signatureHeader) || !signatureHeader.startsWith(SHA256_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "GitHub webhook signature is required");
        }
        String expected = SHA256_PREFIX + hmacSha256Hex(properties.getSecret(), payload);
        if (!MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            signatureHeader.trim().getBytes(StandardCharsets.UTF_8)
        )) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "GitHub webhook signature is invalid");
        }
    }

    private String hmacSha256Hex(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "GitHub webhook signature verification failed");
        }
    }
}
