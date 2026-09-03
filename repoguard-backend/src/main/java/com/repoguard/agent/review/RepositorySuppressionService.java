package com.repoguard.agent.review;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.RepositorySuppressionRequest;
import com.repoguard.agent.dto.RepositorySuppressionResponse;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Explicit, reviewable suppression proposals; active suppressions remain narrow and expiring. */
@Service
public class RepositorySuppressionService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final int MAX_REPLAY = 100;
    private static final int DEFAULT_FEEDBACK_EXPIRY_DAYS = 90;

    private final RepositorySuppressionRepository repository;
    private final ReviewFindingMapper findingMapper;
    private final ReviewRuleRegistry ruleRegistry;

    public RepositorySuppressionService(
        RepositorySuppressionRepository repository,
        ReviewFindingMapper findingMapper,
        ReviewRuleRegistry ruleRegistry
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.findingMapper = Objects.requireNonNull(findingMapper, "findingMapper");
        this.ruleRegistry = Objects.requireNonNull(ruleRegistry, "ruleRegistry");
    }

    @Transactional
    public RepositorySuppressionResponse create(RepositorySuppressionRequest request, String operator) {
        Objects.requireNonNull(request, "request");
        String organization = required(request.organization(), "organization");
        String repositoryName = required(request.repository(), "repository");
        String ruleId = normalizeRule(request.ruleId());
        String fileGlob = optional(request.fileGlob());
        String symbol = optional(request.symbol());
        if (!StringUtils.hasText(fileGlob) && !StringUtils.hasText(symbol)) {
            throw bad("suppression requires fileGlob or symbol");
        }
        validateGlob(fileGlob);
        String reason = required(request.reason(), "reason");
        LocalDateTime expiresAt = parseExpiry(request.expiresAt());
        int previewHits = replay(organization, repositoryName, ruleId, fileGlob, symbol);
        RepositorySuppressionRepository.StoredSuppression stored = repository.insert(
            TenantContext.currentTenantIdOrDefault(), organization, repositoryName, ruleId,
            fileGlob, symbol, reason, operatorName(operator), expiresAt, previewHits
        );
        return response(stored);
    }

    @Transactional
    public RepositorySuppressionResponse createFromFinding(ReviewTask task, ReviewFinding finding, String operator, String note) {
        if (task == null || finding == null || !StringUtils.hasText(finding.getRuleId())
            || !StringUtils.hasText(finding.getFilePath())) {
            return null;
        }
        String reason = StringUtils.hasText(note) ? note.trim() : "finding marked as false positive";
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(DEFAULT_FEEDBACK_EXPIRY_DAYS);
        int previewHits = replay(
            task.getOrganization(), task.getRepository(), normalizeRule(finding.getRuleId()), finding.getFilePath(), null
        );
        RepositorySuppressionRepository.StoredSuppression stored = repository.insert(
            TenantContext.currentTenantIdOrDefault(), task.getOrganization(), task.getRepository(),
            normalizeRule(finding.getRuleId()), finding.getFilePath(), null, reason,
            operatorName(operator), expiresAt, previewHits
        );
        return response(stored);
    }

    public List<RepositorySuppressionResponse> list(String organization, String repositoryName, int limit) {
        return repository.list(
            TenantContext.currentTenantIdOrDefault(), required(organization, "organization"),
            required(repositoryName, "repository"), limit
        ).stream().map(this::response).toList();
    }

    public List<RepositoryPolicyDocument.SuppressionReference> activeReferences(String organization, String repositoryName) {
        return repository.activeFor(
            TenantContext.currentTenantIdOrDefault(), organization, repositoryName
        ).stream().map(RepositorySuppressionRepository.StoredSuppression::toReference).toList();
    }

    @Transactional
    public RepositorySuppressionResponse activate(long id, String operator, String reason) {
        return transition(id, "PROPOSED", "ACTIVE", operator, reason);
    }

    @Transactional
    public RepositorySuppressionResponse revoke(long id, String operator, String reason) {
        return transition(id, "ACTIVE", "REVOKED", operator, reason);
    }

    @Transactional
    public int expireDue(int limit) {
        return repository.expireDue(TenantContext.currentTenantIdOrDefault(), limit);
    }

    private RepositorySuppressionResponse transition(
        long id,
        String expected,
        String next,
        String operator,
        String reason
    ) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        RepositorySuppressionRepository.StoredSuppression existing = repository.find(tenantId, id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Suppression proposal not found: " + id);
        }
        if (!expected.equals(existing.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Suppression proposal is no longer " + expected);
        }
        RepositorySuppressionRepository.StoredSuppression updated = repository.transition(
            tenantId, id, expected, next, operatorName(operator), optional(reason)
        );
        if (updated == null || !next.equals(updated.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Suppression proposal changed concurrently");
        }
        return response(updated);
    }

    private int replay(String organization, String repositoryName, String ruleId, String fileGlob, String symbol) {
        List<ReviewFinding> findings = findingMapper.selectRecentSuppressionHits(
            organization, repositoryName, ruleId, MAX_REPLAY
        );
        if (findings == null) {
            return 0;
        }
        return (int) findings.stream()
            .filter(Objects::nonNull)
            .filter(finding -> !StringUtils.hasText(fileGlob)
                || ReviewRuleApplicability.matchesPathPattern(finding.getFilePath(), fileGlob))
            .filter(finding -> !StringUtils.hasText(symbol)
                || contains(finding.getMessage(), symbol)
                || contains(finding.getRecommendation(), symbol))
            .count();
    }

    private RepositorySuppressionResponse response(RepositorySuppressionRepository.StoredSuppression stored) {
        return new RepositorySuppressionResponse(
            stored.id(), stored.organization(), stored.repository(), stored.ruleId(), stored.fileGlob(),
            stored.symbol(), stored.reason(), stored.status(), stored.operator(),
            format(stored.expiresAt()), stored.previewHitCount(), stored.hitCount(),
            format(stored.createdAt()), format(stored.updatedAt())
        );
    }

    private String normalizeRule(String value) {
        String normalized = required(value, "ruleId").toUpperCase(java.util.Locale.ROOT);
        if (!ruleRegistry.contains(normalized)) {
            throw bad("Unknown review rule: " + normalized);
        }
        return normalized;
    }

    private LocalDateTime parseExpiry(String value) {
        LocalDateTime parsedValue;
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(required(value, "expiresAt"));
            parsedValue = parsed.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ex) {
            try {
                parsedValue = LocalDateTime.parse(required(value, "expiresAt"));
            } catch (DateTimeParseException ignored) {
                throw bad("expiresAt must be an ISO-8601 timestamp");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        if (!parsedValue.isAfter(now) || parsedValue.isAfter(now.plusDays(3_650))) {
            throw bad("expiresAt must be in the future and within 3650 days");
        }
        return parsedValue;
    }

    private void validateGlob(String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.contains("/..")
            || "*".equals(normalized) || "**".equals(normalized) || normalized.length() > 256) {
            throw bad("suppression fileGlob is too broad or unsafe");
        }
    }

    private boolean contains(String value, String token) {
        return StringUtils.hasText(value) && StringUtils.hasText(token)
            && value.toLowerCase(java.util.Locale.ROOT).contains(token.toLowerCase(java.util.Locale.ROOT));
    }

    private String required(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw bad(field + " is required");
        }
        return value.trim();
    }

    private String optional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String operatorName(String value) {
        return StringUtils.hasText(value) ? value.trim().substring(0, Math.min(128, value.trim().length())) : "unknown";
    }

    private String format(LocalDateTime value) {
        return value == null ? null : FORMATTER.format(value);
    }

    private BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
