package com.repoguard.agent.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CiSarifUploadCredentialServiceTest {

    private final ReviewTaskMapper taskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewExecutionAttemptMapper attemptMapper = org.mockito.Mockito.mock(ReviewExecutionAttemptMapper.class);
    private final AuthProperties properties = new AuthProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-03T10:00:00Z"), ZoneOffset.UTC);
    private final CiSarifUploadCredentialService service = new CiSarifUploadCredentialService(
        properties, taskMapper, attemptMapper, clock
    );

    @BeforeEach
    void defaults() {
        properties.setTokenSecret("ci-secret-that-is-long-enough-for-tests");
        when(taskMapper.selectById(9L)).thenReturn(task());
        when(attemptMapper.selectById(17L)).thenReturn(attempt());
    }

    @AfterEach
    void clearTenant() {
        try (TenantContext.Scope _ = TenantContext.withTenant(1L)) {
            // Scope close restores the previous test context.
        }
    }

    @Test
    void issuesTaskBoundShortLivedCredentialAndVerifiesClaims() {
        try (TenantContext.Scope _ = TenantContext.withTenant(7L)) {
            var issue = service.issue(9L, 17L);
            assertThat(issue.token()).startsWith("rgci.");
            assertThat(issue.expiresAt()).isEqualTo(clock.instant().getEpochSecond() + 600);
            var claims = service.verify(issue.token());
            assertThat(claims.tenantId()).isEqualTo(7L);
            assertThat(claims.taskId()).isEqualTo(9L);
            assertThat(claims.attemptId()).isEqualTo(17L);
            assertThat(claims.repository()).isEqualTo("repo");
            assertThat(claims.commitSha()).isEqualTo("abc123");
            assertThat(claims.prNumber()).isEqualTo(42);
        }
    }

    @Test
    void rejectsTamperingExpiredTokensAndMismatchedAttempts() {
        String token;
        try (TenantContext.Scope _ = TenantContext.withTenant(1L)) {
            token = service.issue(9L, 17L).token();
        }
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
        assertThatThrownBy(() -> service.verify(tampered)).isInstanceOf(BusinessException.class);
        CiSarifUploadCredentialService expired = new CiSarifUploadCredentialService(
            properties, taskMapper, attemptMapper,
            Clock.fixed(Instant.parse("2026-09-03T10:11:00Z"), ZoneOffset.UTC)
        );
        assertThatThrownBy(() -> expired.verify(token)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.issue(9L, 18L)).hasMessageContaining("current review attempt");
    }

    @Test
    void rejectsMissingTaskAttemptAndMalformedCredentials() {
        when(taskMapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.issue(9L, 17L)).hasMessageContaining("Review task not found");
        when(taskMapper.selectById(9L)).thenReturn(task());
        when(attemptMapper.selectById(17L)).thenReturn(null);
        assertThatThrownBy(() -> service.issue(9L, 17L)).hasMessageContaining("missing or mismatched");
        assertThatThrownBy(() -> service.verify("bad")).hasMessageContaining("invalid or expired");
        assertThatThrownBy(() -> service.verify("rgci.a.b.c")).hasMessageContaining("invalid or expired");
    }

    @Test
    void rejectsInvalidClaimShapesAndExpiredBoundaries() {
        String[] invalid = {
            tokenWithField(0, "v2"),
            tokenWithField(1, "0"),
            tokenWithField(2, "0"),
            tokenWithField(3, "0"),
            tokenWithField(4, "not-a-number"),
            tokenWithField(5, "!invalid-base64"),
            tokenWithField(6, encode("")),
            tokenWithField(7, encode("")),
            tokenWithField(7, encode("a".repeat(65))),
            tokenWithField(7, encode("abc 123")),
            tokenWithField(8, "1788433261"),
            tokenWithField(9, "0")
        };
        for (String token : invalid) {
            assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid or expired");
        }
    }

    @Test
    void validatesIssueInputsFallbacksAndSigningConfiguration() {
        assertThatThrownBy(() -> service.issue(null, 17L)).hasMessageContaining("Review task not found");
        assertThatThrownBy(() -> service.issue(0L, 17L)).hasMessageContaining("Review task not found");
        assertThatThrownBy(() -> service.issue(9L, null)).hasMessageContaining("current review attempt");
        assertThatThrownBy(() -> service.issue(9L, 0L)).hasMessageContaining("current review attempt");

        ReviewExecutionAttempt fallback = attempt();
        fallback.setCommitSha(null);
        when(attemptMapper.selectById(17L)).thenReturn(fallback);
        assertThat(service.issue(9L, 17L).commitSha()).isEqualTo("abc123");

        AuthProperties missingSecret = new AuthProperties();
        missingSecret.setTokenSecret(" ");
        CiSarifUploadCredentialService unavailable = new CiSarifUploadCredentialService(
            missingSecret, taskMapper, attemptMapper, clock
        );
        assertThatThrownBy(() -> unavailable.issue(9L, 17L))
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("CI credential signing secret is not configured");
        assertThat(new CiSarifUploadCredentialService(properties, taskMapper, attemptMapper)
            .issue(9L, 17L).token()).startsWith("rgci.");
    }

    private String tokenWithField(int index, String value) {
        String token;
        try (TenantContext.Scope _ = TenantContext.withTenant(7L)) {
            token = service.issue(9L, 17L).token();
        }
        String[] parts = token.split("\\.", -1);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String[] fields = payload.split(":", -1);
        fields[index] = value;
        return signedCredential(String.join(":", fields));
    }

    private String signedCredential(String payload) {
        String encoded = encode(payload);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                (properties.getTokenSecret().trim() + "|repoguard-ci-sarif").getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
            return "rgci." + encoded + "." + signature;
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(9L);
        task.setCurrentAttemptId(17L);
        task.setOrganization("org");
        task.setRepository("repo");
        task.setPrNumber(42);
        task.setCommitSha("abc123");
        return task;
    }

    private ReviewExecutionAttempt attempt() {
        ReviewExecutionAttempt attempt = new ReviewExecutionAttempt();
        attempt.setId(17L);
        attempt.setTaskId(9L);
        attempt.setCommitSha("abc123");
        return attempt;
    }
}
