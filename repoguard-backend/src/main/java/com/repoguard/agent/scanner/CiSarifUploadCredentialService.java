package com.repoguard.agent.scanner;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Issues and validates short-lived, task-bound credentials for CI SARIF uploads. */
@Service
public class CiSarifUploadCredentialService {

    private static final String TOKEN_PREFIX = "rgci";
    private static final String PAYLOAD_VERSION = "v1";
    private static final long TTL_SECONDS = 600;
    private static final long CLOCK_SKEW_SECONDS = 60;

    private final AuthProperties authProperties;
    private final ReviewTaskMapper taskMapper;
    private final ReviewExecutionAttemptMapper attemptMapper;
    private final Clock clock;

    public CiSarifUploadCredentialService(
        AuthProperties authProperties,
        ReviewTaskMapper taskMapper,
        ReviewExecutionAttemptMapper attemptMapper
    ) {
        this(authProperties, taskMapper, attemptMapper, Clock.systemUTC());
    }

    CiSarifUploadCredentialService(
        AuthProperties authProperties,
        ReviewTaskMapper taskMapper,
        ReviewExecutionAttemptMapper attemptMapper,
        Clock clock
    ) {
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TokenIssue issue(Long taskId, Long attemptId) {
        ReviewTask task = taskId == null || taskId < 1 ? null : taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        if (attemptId == null || attemptId < 1 || !Objects.equals(task.getCurrentAttemptId(), attemptId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "CI credential must target the current review attempt");
        }
        ReviewExecutionAttempt attempt = attemptMapper.selectById(attemptId);
        validateAttempt(taskId, attempt);
        String commitSha = text(attempt.getCommitSha(), task.getCommitSha());
        if (!StringUtils.hasText(commitSha) || commitSha.length() > 64 || containsWhitespace(commitSha)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The review attempt commit SHA is invalid");
        }
        long now = Instant.now(clock).getEpochSecond();
        long expiresAt = now + TTL_SECONDS;
        String payload = String.join(":",
            PAYLOAD_VERSION,
            Long.toString(TenantContext.currentTenantIdOrDefault()),
            Long.toString(taskId),
            Long.toString(attemptId),
            task.getPrNumber() == null ? "-1" : Integer.toString(task.getPrNumber()),
            encodeField(text(task.getOrganization(), "")),
            encodeField(text(task.getRepository(), "")),
            encodeField(commitSha),
            Long.toString(now),
            Long.toString(expiresAt)
        );
        String encodedPayload = encode(payload.getBytes(StandardCharsets.UTF_8));
        String token = TOKEN_PREFIX + "." + encodedPayload + "." + sign(encodedPayload);
        return new TokenIssue(
            token,
            expiresAt,
            taskId,
            attemptId,
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            commitSha
        );
    }

    public Claims verify(String token) {
        if (!StringUtils.hasText(token) || token.length() > 4096) {
            throw invalidCredential();
        }
        String[] parts = token.trim().split("\\.", -1);
        if (parts.length != 3 || !TOKEN_PREFIX.equals(parts[0]) || parts[1].isBlank() || parts[2].isBlank()) {
            throw invalidCredential();
        }
        if (!signatureMatches(parts[1], parts[2])) {
            throw invalidCredential();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw invalidCredential();
        }
        String[] fields = payload.split(":", -1);
        if (fields.length != 10 || !PAYLOAD_VERSION.equals(fields[0])) {
            throw invalidCredential();
        }
        try {
            long tenantId = positiveLong(fields[1]);
            long taskId = positiveLong(fields[2]);
            long attemptId = positiveLong(fields[3]);
            int prNumber = Integer.parseInt(fields[4]);
            String organization = decodeField(fields[5]);
            String repository = decodeField(fields[6]);
            String commitSha = decodeField(fields[7]);
            long issuedAt = Long.parseLong(fields[8]);
            long expiresAt = Long.parseLong(fields[9]);
            long now = Instant.now(clock).getEpochSecond();
            if (expiresAt <= now || issuedAt > now + CLOCK_SKEW_SECONDS || issuedAt >= expiresAt
                || !StringUtils.hasText(repository) || commitSha.isBlank()
                || commitSha.length() > 64 || containsWhitespace(commitSha)) {
                throw invalidCredential();
            }
            return new Claims(
                tenantId,
                taskId,
                attemptId,
                prNumber < 0 ? null : prNumber,
                organization,
                repository,
                commitSha,
                issuedAt,
                expiresAt
            );
        } catch (NumberFormatException ex) {
            throw invalidCredential();
        }
    }

    private void validateAttempt(Long taskId, ReviewExecutionAttempt attempt) {
        if (attempt == null || !Objects.equals(taskId, attempt.getTaskId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The review attempt is missing or mismatched");
        }
    }

    private long positiveLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 1) {
            throw invalidCredential();
        }
        return parsed;
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }

    private String encodeField(String value) {
        return encode(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeField(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw invalidCredential();
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String sign(String encodedPayload) {
        try {
            String secret = authProperties.getTokenSecret();
            if (!StringUtils.hasText(secret)) {
                throw new IllegalStateException("CI credential signing secret is not configured");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((secret.trim() + "|repoguard-ci-sarif").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return encode(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("CI credential signing is not available", ex);
        }
    }

    private boolean signatureMatches(String payload, String signature) {
        return MessageDigest.isEqual(
            sign(payload).getBytes(StandardCharsets.UTF_8),
            signature.getBytes(StandardCharsets.UTF_8)
        );
    }

    private BusinessException invalidCredential() {
        return new BusinessException(ErrorCode.UNAUTHORIZED, "CI SARIF credential is invalid or expired");
    }

    public record TokenIssue(
        String token,
        long expiresAt,
        Long taskId,
        Long attemptId,
        String organization,
        String repository,
        Integer prNumber,
        String commitSha
    ) {
    }

    public record Claims(
        long tenantId,
        long taskId,
        long attemptId,
        Integer prNumber,
        String organization,
        String repository,
        String commitSha,
        long issuedAt,
        long expiresAt
    ) {
    }
}
