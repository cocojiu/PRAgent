package com.repoguard.agent.review.config;

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
import com.repoguard.agent.review.config.ReviewPolicyPromotionEvidenceStore.CapturedPromotionEvidence;
import com.repoguard.agent.review.config.ReviewRuleQualityGateService.PromotionEvaluation;
import com.repoguard.agent.review.quality.ReviewQualityBaseline;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
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

    public ReviewRuleCommandService(
        ReviewRuleConfigMapper reviewRuleConfigMapper,
        CacheEvictionService cacheEvictionService,
        ReviewRuleConfigPolicy reviewRuleConfigPolicy,
        ReviewRulePolicySnapshotStore policySnapshotStore,
        ReviewRuleQualityGateService qualityGateService,
        ReviewRuleQueryService queryService,
        ReviewPolicyTransactionExecutor transactionExecutor
    ) {
        this.reviewRuleConfigMapper = Objects.requireNonNull(reviewRuleConfigMapper, "reviewRuleConfigMapper");
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
        this.reviewRuleConfigPolicy = Objects.requireNonNull(reviewRuleConfigPolicy, "reviewRuleConfigPolicy");
        this.policySnapshotStore = Objects.requireNonNull(policySnapshotStore, "policySnapshotStore");
        this.qualityGateService = Objects.requireNonNull(qualityGateService, "qualityGateService");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    }

    ReviewRuleConfigDto updateRule(
        String id,
        ReviewRuleConfigRequest request,
        long expectedPolicyVersion
    ) {
        String normalizedId = queryService.normalizeRegisteredRuleId(id);
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
        rule.setDetectorVersion(queryService.detectorVersion(normalizedId));

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
        String normalizedId = queryService.normalizeRegisteredRuleId(id);
        ReviewRuleConfig rule = queryService.loadRule(normalizedId);
        String normalizedStatus = reviewRuleConfigPolicy.normalizeStatus(status);
        long previousPolicyVersion = positiveVersion(rule.getPolicyVersion());
        requireExpectedPolicyVersion(previousPolicyVersion, expectedPolicyVersion);
        rule.setStatus(normalizedStatus);
        rule.setDetectorVersion(queryService.detectorVersion(normalizedId));
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
        String normalizedId = queryService.normalizeRegisteredRuleId(id);
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
        String runtimeDetectorVersion = queryService.detectorVersion(normalizedId);
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
        String falsePositiveGuidance
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
                normalized(rule.getFalsePositiveGuidance())
            );
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }

        private static String normalizedUpper(String value) {
            return normalized(value).toUpperCase(Locale.ROOT);
        }
    }
}
