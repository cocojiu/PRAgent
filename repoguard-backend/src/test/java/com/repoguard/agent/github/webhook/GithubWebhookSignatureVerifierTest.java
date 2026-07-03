package com.repoguard.agent.github.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class GithubWebhookSignatureVerifierTest {

    private static final String SIGNING_KEY = "github-webhook-test-secret";

    private final GithubWebhookProperties properties = properties();
    private final GithubWebhookSignatureVerifier verifier = new GithubWebhookSignatureVerifier(properties);

    @Test
    void acceptsValidSignatureWithSurroundingWhitespace() throws Exception {
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

        assertThatCode(() -> verifier.verify(" " + signature(payload) + " ", payload))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingSignaturePrefixAsRequiredSignature() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify("md5=abc", payload))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                org.assertj.core.api.Assertions.assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED))
            .hasMessageContaining("required");
    }

    @Test
    void rejectsNonHexSignatureDigestBeforeComparison() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify("sha256=" + "z".repeat(64), payload))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                org.assertj.core.api.Assertions.assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED))
            .hasMessageContaining("invalid");
    }

    @Test
    void rejectsOverlongSignatureDigestBeforeComparison() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify("sha256=" + "a".repeat(65), payload))
            .isInstanceOfSatisfying(BusinessException.class, ex ->
                org.assertj.core.api.Assertions.assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED))
            .hasMessageContaining("invalid");
    }

    private GithubWebhookProperties properties() {
        GithubWebhookProperties result = new GithubWebhookProperties();
        result.setSecret(SIGNING_KEY);
        return result;
    }

    private String signature(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
