package com.repoguard.agent.review.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRulePolicyVersionDto;
import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.dto.ReviewQualityGroupDto;
import com.repoguard.agent.dto.ReviewRuleFeedbackStat;
import com.repoguard.agent.dto.ReviewRuleHitCount;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.entity.ReviewRulePolicySnapshot;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import com.repoguard.agent.review.ReviewFindingProjectionAssembler;
import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.ReviewRuleRegistry;
import com.repoguard.agent.review.quality.ReviewQualityBaseline;
import com.repoguard.agent.review.quality.ReviewQualityBaselineService;
import com.repoguard.agent.service.ReviewCalibrationService;
import com.repoguard.agent.service.ReviewRuleConfigService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ReviewRuleConfigServiceImpl implements ReviewRuleConfigService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewRuleConfigMapper reviewRuleConfigMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final CacheEvictionService cacheEvictionService;
    private final ReviewRuleConfigPolicy reviewRuleConfigPolicy;
    private final ReviewRuleMetricAssembler reviewRuleMetricAssembler;
    private final ReviewQualityBaselineService reviewQualityBaselineService;
    private final ReviewRuleRegistry reviewRuleRegistry;
    private final ReviewRulePolicySnapshotStore policySnapshotStore;
    private final ReviewRuleLifecycleGate lifecycleGate;
    private final ReviewStrategyPolicyService strategyPolicyService;
    private final ReviewCalibrationService reviewCalibrationService;

    @Autowired
    public ReviewRuleConfigServiceImpl(
        ReviewRuleConfigMapper reviewRuleConfigMapper,
        ReviewFindingMapper reviewFindingMapper,
        CacheEvictionService cacheEvictionService,
        ReviewRuleConfigPolicy reviewRuleConfigPolicy,
        ReviewRuleMetricAssembler reviewRuleMetricAssembler,
        ReviewQualityBaselineService reviewQualityBaselineService,
        ReviewRuleRegistry reviewRuleRegistry,
        ReviewRulePolicySnapshotStore policySnapshotStore,
        ReviewRuleLifecycleGate lifecycleGate,
        ReviewStrategyPolicyService strategyPolicyService,
        ReviewCalibrationService reviewCalibrationService
    ) {
        this.reviewRuleConfigMapper = Objects.requireNonNull(
            reviewRuleConfigMapper,
            "reviewRuleConfigMapper must not be null"
        );
        this.reviewFindingMapper = Objects.requireNonNull(reviewFindingMapper, "reviewFindingMapper must not be null");
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
        this.reviewRuleConfigPolicy =
            Objects.requireNonNull(reviewRuleConfigPolicy, "reviewRuleConfigPolicy must not be null");
        this.reviewRuleMetricAssembler =
            Objects.requireNonNull(reviewRuleMetricAssembler, "reviewRuleMetricAssembler must not be null");
        this.reviewQualityBaselineService =
            Objects.requireNonNull(reviewQualityBaselineService, "reviewQualityBaselineService must not be null");
        this.reviewRuleRegistry = Objects.requireNonNull(reviewRuleRegistry, "reviewRuleRegistry must not be null");
        this.policySnapshotStore = Objects.requireNonNull(policySnapshotStore, "policySnapshotStore");
        this.lifecycleGate = Objects.requireNonNull(lifecycleGate, "lifecycleGate");
        this.strategyPolicyService = Objects.requireNonNull(strategyPolicyService, "strategyPolicyService");
        this.reviewCalibrationService = Objects.requireNonNull(
            reviewCalibrationService,
            "reviewCalibrationService"
        );
    }

    public ReviewRuleConfigServiceImpl(
        ReviewRuleConfigMapper reviewRuleConfigMapper,
        ReviewFindingMapper reviewFindingMapper,
        CacheEvictionService cacheEvictionService,
        ReviewRuleConfigPolicy reviewRuleConfigPolicy,
        ReviewRuleMetricAssembler reviewRuleMetricAssembler,
        ReviewQualityBaselineService reviewQualityBaselineService,
        ReviewRuleRegistry reviewRuleRegistry
    ) {
        this.reviewRuleConfigMapper = Objects.requireNonNull(
            reviewRuleConfigMapper,
            "reviewRuleConfigMapper must not be null"
        );
        this.reviewFindingMapper = Objects.requireNonNull(reviewFindingMapper, "reviewFindingMapper must not be null");
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
        this.reviewRuleConfigPolicy = Objects.requireNonNull(
            reviewRuleConfigPolicy,
            "reviewRuleConfigPolicy must not be null"
        );
        this.reviewRuleMetricAssembler = Objects.requireNonNull(
            reviewRuleMetricAssembler,
            "reviewRuleMetricAssembler must not be null"
        );
        this.reviewQualityBaselineService = Objects.requireNonNull(
            reviewQualityBaselineService,
            "reviewQualityBaselineService must not be null"
        );
        this.reviewRuleRegistry = Objects.requireNonNull(reviewRuleRegistry, "reviewRuleRegistry must not be null");
        this.policySnapshotStore = null;
        this.lifecycleGate = new ReviewRuleLifecycleGate();
        this.strategyPolicyService = null;
        this.reviewCalibrationService = null;
    }

    @Override
    @Cacheable(cacheNames = CacheNames.REVIEW_RULES)
    public ReviewRulesResponse getReviewRules() {
        List<ReviewRuleConfig> rules = reviewRuleConfigMapper.selectList(
            new LambdaQueryWrapper<ReviewRuleConfig>()
                .orderByAsc(ReviewRuleConfig::getSortOrder)
                .orderByAsc(ReviewRuleConfig::getId)
        );
        Map<String, Long> hitCountByRule = loadRuleHitCounts();
        ReviewRuleFeedbackStat feedbackStat = loadRuleFeedbackStat();
        ReviewQualityBaseline qualityBaseline = reviewQualityBaselineService.loadBaseline();
        List<ReviewRuleConfigDto> ruleDtos = rules.stream()
            .filter(rule -> reviewRuleRegistry.contains(rule.getId()))
            .map(rule -> toReviewRuleDto(
                rule,
                hitCountByRule.getOrDefault(rule.getId(), 0L),
                qualityBaseline
            ))
            .toList();
        return new ReviewRulesResponse(
            reviewRuleMetricAssembler.buildRuleMetrics(rules, feedbackStat, qualityBaseline),
            ruleDtos,
            qualityBaseline.groups().stream().map(ReviewQualityGroupDto::from).toList(),
            strategyPolicyService == null ? null : strategyPolicyService.getActive(qualityBaseline)
        );
    }

    @Override
    @Transactional
    public ReviewRuleConfigDto createReviewRule(ReviewRuleConfigRequest request) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "Dynamic review rule creation is disabled; only registered built-in rules can be edited"
        );
    }

    @Override
    @Transactional
    public ReviewRuleConfigDto updateReviewRule(String id, ReviewRuleConfigRequest request) {
        String normalizedId = reviewRuleConfigPolicy.normalizeRuleId(id);
        ensureRegistered(normalizedId);
        if (!normalizedId.equals(reviewRuleConfigPolicy.normalizeRuleId(request.id()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review rule id in path and body must match");
        }
        ReviewRuleConfig rule = loadReviewRule(normalizedId);
        RuleSemanticState previousSemantics = RuleSemanticState.from(rule);
        String previousStatus = rule.getStatus();
        EnforcementMode previousMode = EnforcementMode.from(rule.getEnforcementMode());
        long previousPolicyVersion = positiveVersion(rule.getPolicyVersion());
        long previousConfigVersion = positiveVersion(rule.getConfigVersion());
        applyReviewRuleRequest(rule, normalizedId, request);
        rule.setDetectorVersion(reviewRuleRegistry.detectorVersion(normalizedId));
        boolean semanticChange = !previousSemantics.equals(RuleSemanticState.from(rule));
        boolean enabling = "DISABLED".equalsIgnoreCase(previousStatus)
            && "ENABLED".equalsIgnoreCase(rule.getStatus());
        String changeType;
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
            } else {
                ReviewRuleQualityGateDto qualityGate = promotionQualityGate(
                    normalizedId,
                    previousConfigVersion
                );
                validateTransition(previousMode, targetMode, qualityGate);
                changeType = rank(targetMode) > rank(previousMode) ? "PROMOTION" : "POLICY_UPDATE";
            }
        }
        rule.setPolicyVersion(previousPolicyVersion + 1);
        rule.setUpdatedAt(LocalDateTime.now());
        reviewRuleConfigMapper.updateById(rule);
        saveSnapshot(rule, changeType, previousPolicyVersion);
        evictRuleCaches();
        return toReviewRuleDto(
            rule,
            loadRuleHitCounts().getOrDefault(rule.getId(), 0L),
            reviewQualityBaselineService.loadBaseline()
        );
    }

    @Override
    @Transactional
    public ReviewRuleConfigDto updateReviewRuleStatus(String id, String status) {
        String normalizedId = reviewRuleConfigPolicy.normalizeRuleId(id);
        ensureRegistered(normalizedId);
        ReviewRuleConfig rule = loadReviewRule(normalizedId);
        String normalizedStatus = reviewRuleConfigPolicy.normalizeStatus(status);
        long previousPolicyVersion = positiveVersion(rule.getPolicyVersion());
        rule.setStatus(normalizedStatus);
        rule.setDetectorVersion(reviewRuleRegistry.detectorVersion(normalizedId));
        rule.setConfigVersion(positiveVersion(rule.getConfigVersion()));
        rule.setPolicyVersion(previousPolicyVersion + 1);
        if ("ENABLED".equals(normalizedStatus)) {
            rule.setEnforcementMode(EnforcementMode.OBSERVE.name());
        }
        rule.setUpdatedAt(LocalDateTime.now());
        reviewRuleConfigMapper.updateById(rule);
        saveSnapshot(
            rule,
            "ENABLED".equals(normalizedStatus) ? "ENABLE_OBSERVE" : "DISABLE",
            previousPolicyVersion
        );
        evictRuleCaches();
        return toReviewRuleDto(
            rule,
            loadRuleHitCounts().getOrDefault(rule.getId(), 0L),
            reviewQualityBaselineService.loadBaseline()
        );
    }

    @Override
    public List<ReviewRulePolicyVersionDto> getReviewRuleVersions(String id) {
        requireSnapshotStore();
        String normalizedId = reviewRuleConfigPolicy.normalizeRuleId(id);
        ensureRegistered(normalizedId);
        ReviewRuleConfig active = loadReviewRule(normalizedId);
        long activePolicyVersion = positiveVersion(active.getPolicyVersion());
        return policySnapshotStore.list(normalizedId).stream()
            .map(snapshot -> toVersionDto(snapshot, activePolicyVersion))
            .toList();
    }

    @Override
    @Transactional
    public ReviewRuleConfigDto rollbackReviewRule(String id, long policyVersion) {
        requireSnapshotStore();
        String normalizedId = reviewRuleConfigPolicy.normalizeRuleId(id);
        ensureRegistered(normalizedId);
        ReviewRuleConfig rule = loadReviewRule(normalizedId);
        ReviewRulePolicySnapshot snapshot = policySnapshotStore.find(normalizedId, policyVersion);
        if (snapshot == null) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Review rule policy snapshot not found: " + normalizedId + "@" + policyVersion
            );
        }
        String runtimeDetectorVersion = reviewRuleRegistry.detectorVersion(normalizedId);
        if (!runtimeDetectorVersion.equals(snapshot.getDetectorVersion())) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Review rule detector version is unsupported by the current runtime"
            );
        }
        long newPolicyVersion = positiveVersion(rule.getPolicyVersion()) + 1;
        policySnapshotStore.restore(rule, snapshot, newPolicyVersion);
        reviewRuleConfigMapper.updateById(rule);
        saveSnapshot(rule, "ROLLBACK", policyVersion);
        evictRuleCaches();
        return toReviewRuleDto(
            rule,
            loadRuleHitCounts().getOrDefault(rule.getId(), 0L),
            reviewQualityBaselineService.loadBaseline()
        );
    }

    private void evictRuleCaches() {
        cacheEvictionService.evictReviewRules();
        cacheEvictionService.evictDashboardRules();
    }

    private void saveSnapshot(ReviewRuleConfig rule, String changeType, Long sourcePolicyVersion) {
        if (policySnapshotStore != null) {
            policySnapshotStore.save(rule, changeType, sourcePolicyVersion);
        }
    }

    private void requireSnapshotStore() {
        if (policySnapshotStore == null) {
            throw new IllegalStateException("Review rule policy snapshot store is unavailable");
        }
    }

    private ReviewRuleConfig loadReviewRule(String id) {
        ReviewRuleConfig rule = reviewRuleConfigMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review rule not found: " + id);
        }
        return rule;
    }

    private void applyReviewRuleRequest(ReviewRuleConfig rule, String id, ReviewRuleConfigRequest request) {
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

    private int nextRuleSortOrder() {
        List<ReviewRuleConfig> rules = reviewRuleConfigMapper.selectList(
            new LambdaQueryWrapper<ReviewRuleConfig>().orderByDesc(ReviewRuleConfig::getSortOrder)
        );
        return reviewRuleConfigPolicy.nextSortOrder(rules);
    }

    private Map<String, Long> loadRuleHitCounts() {
        return buildRuleHitCounts(
            ReviewFindingProjectionAssembler.toRuleHitDtos(reviewFindingMapper.selectReviewRuleHitCounts())
        );
    }

    private ReviewRuleFeedbackStat loadRuleFeedbackStat() {
        ReviewRuleFeedbackStat feedbackStat = ReviewFindingProjectionAssembler.toDto(
            reviewFindingMapper.selectReviewRuleFeedbackStat()
        );
        return feedbackStat == null ? new ReviewRuleFeedbackStat() : feedbackStat;
    }

    private Map<String, Long> buildRuleHitCounts(List<ReviewRuleHitCount> hitCounts) {
        if (hitCounts == null || hitCounts.isEmpty()) {
            return Map.of();
        }
        return hitCounts.stream()
            .filter(count -> count != null && StringUtils.hasText(count.getRuleId()))
            .collect(Collectors.toMap(
                ReviewRuleHitCount::getRuleId,
                count -> count.getTotal() == null ? 0L : count.getTotal(),
                Long::sum
            ));
    }

    private ReviewRuleConfigDto toReviewRuleDto(
        ReviewRuleConfig rule,
        long hitCount,
        ReviewQualityBaseline baseline
    ) {
        long configVersion = positiveVersion(rule.getConfigVersion());
        List<com.repoguard.agent.review.quality.ReviewQualityGroupBaseline> qualityGroups = baseline == null
            ? List.of()
            : baseline.groups();
        return new ReviewRuleConfigDto(
            rule.getId(),
            rule.getRuleName(),
            rule.getScope(),
            defaultString(rule.getApplicableLanguages()),
            defaultString(rule.getFilePatterns()),
            lower(rule.getSeverity()),
            lower(rule.getStatus()),
            hitCount,
            (rule.getConfidence() == null ? 0 : rule.getConfidence()) + "%",
            format(rule.getUpdatedAt()),
            rule.getDescription(),
            defaultString(rule.getPositiveExample()),
            defaultString(rule.getFalsePositiveGuidance()),
            lower(rule.getEnforcementMode()),
            reviewRuleRegistry.detectorVersion(rule.getId()),
            configVersion,
            positiveVersion(rule.getPolicyVersion()),
            lifecycleGate.evaluate(
                rule.getId(),
                reviewRuleRegistry.detectorVersion(rule.getId()),
                configVersion,
                qualityGroups
            )
        );
    }

    private ReviewRulePolicyVersionDto toVersionDto(
        ReviewRulePolicySnapshot snapshot,
        long activePolicyVersion
    ) {
        return new ReviewRulePolicyVersionDto(
            positiveVersion(snapshot.getPolicyVersion()),
            positiveVersion(snapshot.getConfigVersion()),
            snapshot.getDetectorVersion(),
            lower(snapshot.getSeverity()),
            lower(snapshot.getStatus()),
            (snapshot.getConfidence() == null ? 0 : snapshot.getConfidence()) + "%",
            lower(snapshot.getEnforcementMode()),
            snapshot.getChangeType(),
            snapshot.getSourcePolicyVersion(),
            format(snapshot.getCreatedAt()),
            positiveVersion(snapshot.getPolicyVersion()) == activePolicyVersion
        );
    }

    private void validateTransition(
        EnforcementMode current,
        EnforcementMode target,
        ReviewRuleQualityGateDto qualityGate
    ) {
        if (rank(target) <= rank(current)) {
            return;
        }
        if (current == EnforcementMode.OBSERVE && target == EnforcementMode.BLOCK) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review rule must pass COMMENT before BLOCK");
        }
        if (target == EnforcementMode.COMMENT && !qualityGate.commentEligible()) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "At least one explicit labeled sample is required before COMMENT"
            );
        }
        if (target == EnforcementMode.BLOCK && !qualityGate.blockEligible()) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "BLOCK quality gate failed: " + String.join(",", qualityGate.blockers())
            );
        }
    }

    private int rank(EnforcementMode mode) {
        return switch (mode) {
            case OBSERVE -> 1;
            case COMMENT -> 2;
            case BLOCK -> 3;
        };
    }

    private long positiveVersion(Long value) {
        return value == null || value < 1 ? 1 : value;
    }

    private void ensureRegistered(String id) {
        if (!reviewRuleRegistry.contains(id)) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Review rule has no registered detector implementation: " + id
            );
        }
    }

    private ReviewRuleQualityGateDto promotionQualityGate(String ruleId, long configVersion) {
        if (reviewCalibrationService != null) {
            return reviewCalibrationService.getQueue(ruleId, 1, false).qualityGate();
        }
        return lifecycleGate.evaluate(
            ruleId,
            reviewRuleRegistry.detectorVersion(ruleId),
            configVersion,
            reviewQualityBaselineService.loadBaseline().groups()
        );
    }

    private String cleanOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
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
