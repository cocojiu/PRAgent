package com.repoguard.agent.review.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.entity.ReviewRulePolicySnapshot;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.DeclarativeRulePolicy;
import com.repoguard.agent.review.config.ReviewPolicyPromotionEvidenceStore.CapturedPromotionEvidence;
import com.repoguard.agent.review.config.ReviewRuleQualityGateService.PromotionEvaluation;
import com.repoguard.agent.review.quality.ReviewQualityBaseline;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewRuleCommandService {

    private final ReviewRuleConfigMapper reviewRuleConfigMapper;
    private final CacheEvictionService cacheEvictionService;
    private final ReviewRuleConfigPolicy reviewRuleConfigPolicy;
    private final ReviewRulePolicySnapshotStore policySnapshotStore;
    private final ReviewRuleQualityGateService qualityGateService;
    private final ReviewRuleQueryService queryService;
    private final ReviewPolicyTransactionExecutor transactionExecutor;
    private final DeclarativeRulePolicy declarativeRulePolicy;

    @Autowired
    public ReviewRuleCommandService(
        ReviewRuleConfigMapper reviewRuleConfigMapper,
        CacheEvictionService cacheEvictionService,
        ReviewRuleConfigPolicy reviewRuleConfigPolicy,
        ReviewRulePolicySnapshotStore policySnapshotStore,
        ReviewRuleQualityGateService qualityGateService,
        ReviewRuleQueryService queryService,
        ReviewPolicyTransactionExecutor transactionExecutor,
        DeclarativeRulePolicy declarativeRulePolicy
    ) {
        this.reviewRuleConfigMapper = Objects.requireNonNull(reviewRuleConfigMapper, "reviewRuleConfigMapper");
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
        this.reviewRuleConfigPolicy = Objects.requireNonNull(reviewRuleConfigPolicy, "reviewRuleConfigPolicy");
        this.policySnapshotStore = Objects.requireNonNull(policySnapshotStore, "policySnapshotStore");
        this.qualityGateService = Objects.requireNonNull(qualityGateService, "qualityGateService");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.declarativeRulePolicy = Objects.requireNonNull(declarativeRulePolicy, "declarativeRulePolicy");
    }

    public ReviewRuleCommandService(
        ReviewRuleConfigMapper reviewRuleConfigMapper,
        CacheEvictionService cacheEvictionService,
        ReviewRuleConfigPolicy reviewRuleConfigPolicy,
        ReviewRulePolicySnapshotStore policySnapshotStore,
        ReviewRuleQualityGateService qualityGateService,
        ReviewRuleQueryService queryService,
        ReviewPolicyTransactionExecutor transactionExecutor
    ) {
        this(
            reviewRuleConfigMapper,
            cacheEvictionService,
            reviewRuleConfigPolicy,
            policySnapshotStore,
            qualityGateService,
            queryService,
            transactionExecutor,
            new DeclarativeRulePolicy()
        );
    }

    ReviewRuleConfigDto createRule(ReviewRuleConfigRequest request) {
        Objects.requireNonNull(request, "request");
        String normalizedId = reviewRuleConfigPolicy.normalizeRuleId(request.id());
        if (queryService.isRegistered(normalizedId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Built-in review rules cannot be created");
        }
        if (reviewRuleConfigMapper.selectById(normalizedId) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Review rule already exists: " + normalizedId);
        }
        String detectorType = declarativeRulePolicy.normalizeType(request.detectorType());
        if (!declarativeRulePolicy.isDeclarativeType(detectorType)) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Dynamic review rule creation requires REGEX or AST detectorType"
            );
        }
        try {
            declarativeRulePolicy.validate(detectorType, request.matcherExpression(), request.exceptionPatterns());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
        ReviewRuleConfig rule = new ReviewRuleConfig();
        applyRequest(rule, normalizedId, request);
        rule.setDetectorType(detectorType);
        rule.setDetectorVersion(declarativeRulePolicy.detectorVersion(detectorType));
        rule.setConfigVersion(1L);
        rule.setPolicyVersion(1L);
        rule.setSortOrder(reviewRuleConfigPolicy.nextSortOrder(
            reviewRuleConfigMapper.selectList(new LambdaQueryWrapper<ReviewRuleConfig>())
        ));
        if ("ENABLED".equalsIgnoreCase(rule.getStatus())) {
            rule.setEnforcementMode(EnforcementMode.OBSERVE.name());
        }
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        transactionExecutor.write(() -> {
            if (reviewRuleConfigMapper.insert(rule) != 1) {
                throw new IllegalStateException("Failed to create declarative review rule");
            }
            policySnapshotStore.save(rule, "BASELINE", null);
            return null;
        });
        evictRuleCaches();
        return queryService.toRuleDto(rule, queryService.loadBaseline());
    }

    ReviewRuleConfigDto updateRule(
        String id,
        ReviewRuleConfigRequest request,
        long expectedPolicyVersion
    ) {
        String normalizedId = queryService.normalizeRuleId(id);
        if (!normalizedId.equals(reviewRuleConfigPolicy.normalizeRuleId(request.id()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review rule id in path and body must match");
        }
        ReviewRuleConfig rule = queryService.loadRule(normalizedId);
        RuleSemanticState previousSemantics = RuleSemanticState.from(rule);
        String previousStatus = rule.getStatus();
        EnforcementMode previousMode = EnforcementMode.from(rule.getEnforcementMode());
        long previousPolicyVersion = positiveVersion(rule.getPolicyVersion());
        requireExpectedPolicyVersion(previousPolicyVersion, expectedPolicyVersion);
        long previousConfigVersion = positiveVersion(rule.getConfigVersion());
        applyRequest(rule, normalizedId, request);
        rule.setDetectorVersion(queryService.detectorVersion(rule));

        boolean semanticChange = !previousSemantics.equals(RuleSemanticState.from(rule));
        boolean enabling = "DISABLED".equalsIgnoreCase(previousStatus)
            && "ENABLED".equalsIgnoreCase(rule.getStatus());
        String changeType;
        PromotionEvaluation promotionEvaluation = null;
        EnforcementMode promotionTargetMode = null;
        if (semanticChange) {
            rule.setConfigVersion(previousConfigVersion + 1);
            rule.setEnforcementMode(EnforcementMode.OBSERVE.name());
            changeType = "CONFIG_UPDATE_OBSERVE";
        } else {
            rule.setConfigVersion(previousConfigVersion);
            EnforcementMode targetMode = EnforcementMode.from(rule.getEnforcementMode());
            if (enabling || "DISABLED".equalsIgnoreCase(rule.getStatus())) {
                rule.setEnforcementMode(EnforcementMode.OBSERVE.name());
                changeType = enabling ? "ENABLE_OBSERVE" : "DISABLE";
            } else if (rank(targetMode) > rank(previousMode)) {
                PromotionEvaluation evaluation = qualityGateService.evaluatePromotion(normalizedId);
                qualityGateService.validateTransition(previousMode, targetMode, evaluation.qualityGate());
                promotionEvaluation = evaluation;
                promotionTargetMode = targetMode;
                changeType = "PROMOTION";
            } else {
                changeType = "POLICY_UPDATE";
            }
        }
        rule.setPolicyVersion(previousPolicyVersion + 1);
        rule.setUpdatedAt(LocalDateTime.now());

        CapturedPromotionEvidence capturedEvidence = promotionEvaluation == null
            ? null
            : qualityGateService.capturePromotion(previousMode, promotionTargetMode, promotionEvaluation);
        ReviewQualityBaseline responseBaseline = queryService.loadBaseline();
        transactionExecutor.write(() -> {
            updateRuleIfCurrent(rule, previousPolicyVersion);
            ReviewRulePolicySnapshot savedSnapshot = saveSnapshot(rule, changeType, previousPolicyVersion);
            if (capturedEvidence != null) {
                if (savedSnapshot == null) {
                    throw new IllegalStateException("Rule promotion snapshot is unavailable");
                }
                qualityGateService.recordPromotion(savedSnapshot, capturedEvidence);
            }
            return null;
        });
        evictRuleCaches();
        return queryService.toRuleDto(rule, responseBaseline);
    }

    ReviewRuleConfigDto updateStatus(String id, String status, long expectedPolicyVersion) {
        String normalizedId = queryService.normalizeRuleId(id);
        ReviewRuleConfig rule = queryService.loadRule(normalizedId);
        String normalizedStatus = reviewRuleConfigPolicy.normalizeStatus(status);
        long previousPolicyVersion = positiveVersion(rule.getPolicyVersion());
        requireExpectedPolicyVersion(previousPolicyVersion, expectedPolicyVersion);
        rule.setStatus(normalizedStatus);
        rule.setDetectorVersion(queryService.detectorVersion(rule));
        rule.setConfigVersion(positiveVersion(rule.getConfigVersion()));
        rule.setPolicyVersion(previousPolicyVersion + 1);
        if ("ENABLED".equals(normalizedStatus)) {
            rule.setEnforcementMode(EnforcementMode.OBSERVE.name());
        }
        rule.setUpdatedAt(LocalDateTime.now());
        ReviewQualityBaseline responseBaseline = queryService.loadBaseline();
        transactionExecutor.write(() -> {
            updateRuleIfCurrent(rule, previousPolicyVersion);
            saveSnapshot(
                rule,
                "ENABLED".equals(normalizedStatus) ? "ENABLE_OBSERVE" : "DISABLE",
                previousPolicyVersion
            );
            return null;
        });
        evictRuleCaches();
        return queryService.toRuleDto(rule, responseBaseline);
    }

    ReviewRuleConfigDto rollback(String id, long policyVersion, long expectedPolicyVersion) {
        String normalizedId = queryService.normalizeRuleId(id);
        ReviewRuleConfig rule = queryService.loadRule(normalizedId);
        long currentPolicyVersion = positiveVersion(rule.getPolicyVersion());
        requireExpectedPolicyVersion(currentPolicyVersion, expectedPolicyVersion);
        ReviewRulePolicySnapshot snapshot = policySnapshotStore.find(normalizedId, policyVersion);
        if (snapshot == null) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Review rule policy snapshot not found: " + normalizedId + "@" + policyVersion
            );
        }
        String runtimeDetectorVersion = queryService.detectorVersion(rule);
        if (!runtimeDetectorVersion.equals(snapshot.getDetectorVersion())) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Review rule detector version is unsupported by the current runtime"
            );
        }
        policySnapshotStore.restore(rule, snapshot, currentPolicyVersion + 1);
        ReviewQualityBaseline responseBaseline = queryService.loadBaseline();
        transactionExecutor.write(() -> {
            updateRuleIfCurrent(rule, currentPolicyVersion);
            policySnapshotStore.save(rule, "ROLLBACK", policyVersion);
            return null;
        });
        evictRuleCaches();
        return queryService.toRuleDto(rule, responseBaseline);
    }

    private void updateRuleIfCurrent(ReviewRuleConfig rule, long expectedPolicyVersion) {
        int updated = reviewRuleConfigMapper.update(
            rule,
            new LambdaUpdateWrapper<ReviewRuleConfig>()
                .eq(ReviewRuleConfig::getId, rule.getId())
                .eq(ReviewRuleConfig::getPolicyVersion, expectedPolicyVersion)
        );
        if (updated != 1) {
            throw policyConflict();
        }
    }

    private void requireExpectedPolicyVersion(long currentPolicyVersion, long expectedPolicyVersion) {
        if (currentPolicyVersion != expectedPolicyVersion) {
            throw policyConflict();
        }
    }

    private BusinessException policyConflict() {
        return new BusinessException(ErrorCode.CONFLICT, "Review rule policy changed; reload and retry");
    }

    private ReviewRulePolicySnapshot saveSnapshot(
        ReviewRuleConfig rule,
        String changeType,
        Long sourcePolicyVersion
    ) {
        return policySnapshotStore.save(rule, changeType, sourcePolicyVersion);
    }

    private int rank(EnforcementMode mode) {
        return switch (mode) {
            case OBSERVE -> 1;
            case COMMENT -> 2;
            case BLOCK -> 3;
        };
    }

    private void applyRequest(ReviewRuleConfig rule, String id, ReviewRuleConfigRequest request) {
        rule.setId(id);
        rule.setRuleName(request.name().trim());
        rule.setScope(request.scope().trim());
        rule.setApplicableLanguages(cleanOptional(request.applicableLanguages()));
        rule.setFilePatterns(cleanOptional(request.filePatterns()));
        rule.setSeverity(reviewRuleConfigPolicy.normalizeSeverity(request.severity()));
        rule.setStatus(reviewRuleConfigPolicy.normalizeStatus(request.status()));
        rule.setConfidence(request.confidence() == null ? 90 : request.confidence());
        rule.setEnforcementMode(reviewRuleConfigPolicy.normalizeEnforcementMode(request.enforcementMode()));
        rule.setDescription(request.description().trim());
        rule.setPositiveExample(cleanOptional(request.positiveExample()));
        rule.setFalsePositiveGuidance(cleanOptional(request.falsePositiveGuidance()));
        String requestedType = StringUtils.hasText(request.detectorType())
            ? declarativeRulePolicy.normalizeType(request.detectorType())
            : declarativeRulePolicy.normalizeType(rule.getDetectorType());
        if (StringUtils.hasText(rule.getDetectorType()) && rule.getDetectorType().equalsIgnoreCase(DeclarativeRulePolicy.BUILTIN)
            && !StringUtils.hasText(request.detectorType())) {
            requestedType = DeclarativeRulePolicy.BUILTIN;
        }
        if (rule.getDetectorType() != null && declarativeRulePolicy.isDeclarativeType(rule.getDetectorType())
            && !StringUtils.hasText(request.detectorType())) {
            requestedType = declarativeRulePolicy.normalizeType(rule.getDetectorType());
        }
        try {
            declarativeRulePolicy.validate(requestedType, request.matcherExpression(), request.exceptionPatterns());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
        rule.setDetectorType(requestedType);
        rule.setMatcherExpression(cleanOptional(request.matcherExpression()));
        rule.setExceptionPatterns(cleanOptional(request.exceptionPatterns()));
    }

    private void evictRuleCaches() {
        cacheEvictionService.evictReviewRules();
        cacheEvictionService.evictDashboardRules();
    }

    private long positiveVersion(Long value) {
        return value == null || value < 1 ? 1 : value;
    }

    private String cleanOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private record RuleSemanticState(
        String ruleName,
        String scope,
        String applicableLanguages,
        String filePatterns,
        String severity,
        Integer confidence,
        String description,
        String positiveExample,
        String falsePositiveGuidance,
        String detectorType,
        String matcherExpression,
        String exceptionPatterns
    ) {
        static RuleSemanticState from(ReviewRuleConfig rule) {
            return new RuleSemanticState(
                normalized(rule.getRuleName()),
                normalized(rule.getScope()),
                normalized(rule.getApplicableLanguages()),
                normalized(rule.getFilePatterns()),
                normalizedUpper(rule.getSeverity()),
                rule.getConfidence(),
                normalized(rule.getDescription()),
                normalized(rule.getPositiveExample()),
                normalized(rule.getFalsePositiveGuidance()),
                normalizedDetectorType(rule.getDetectorType()),
                normalized(rule.getMatcherExpression()),
                normalized(rule.getExceptionPatterns())
            );
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }

        private static String normalizedUpper(String value) {
            return normalized(value).toUpperCase(Locale.ROOT);
        }

        private static String normalizedDetectorType(String value) {
            String normalized = normalizedUpper(value);
            return normalized.isBlank() ? DeclarativeRulePolicy.BUILTIN : normalized;
        }
    }
}
