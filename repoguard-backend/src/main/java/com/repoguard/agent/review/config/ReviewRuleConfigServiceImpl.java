package com.repoguard.agent.review.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRuleFeedbackStat;
import com.repoguard.agent.dto.ReviewRuleHitCount;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import com.repoguard.agent.review.ReviewFindingProjectionAssembler;
import com.repoguard.agent.review.ReviewRuleRegistry;
import com.repoguard.agent.review.quality.ReviewQualityBaseline;
import com.repoguard.agent.review.quality.ReviewQualityBaselineService;
import com.repoguard.agent.service.ReviewRuleConfigService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
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
        this.reviewRuleConfigPolicy =
            Objects.requireNonNull(reviewRuleConfigPolicy, "reviewRuleConfigPolicy must not be null");
        this.reviewRuleMetricAssembler =
            Objects.requireNonNull(reviewRuleMetricAssembler, "reviewRuleMetricAssembler must not be null");
        this.reviewQualityBaselineService =
            Objects.requireNonNull(reviewQualityBaselineService, "reviewQualityBaselineService must not be null");
        this.reviewRuleRegistry = Objects.requireNonNull(reviewRuleRegistry, "reviewRuleRegistry must not be null");
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
            .map(rule -> toReviewRuleDto(rule, hitCountByRule.getOrDefault(rule.getId(), 0L)))
            .toList();
        return new ReviewRulesResponse(
            reviewRuleMetricAssembler.buildRuleMetrics(rules, feedbackStat, qualityBaseline),
            ruleDtos
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
        applyReviewRuleRequest(rule, normalizedId, request);
        rule.setUpdatedAt(LocalDateTime.now());
        reviewRuleConfigMapper.updateById(rule);
        evictRuleCaches();
        return toReviewRuleDto(rule, loadRuleHitCounts().getOrDefault(rule.getId(), 0L));
    }

    @Override
    @Transactional
    public ReviewRuleConfigDto updateReviewRuleStatus(String id, String status) {
        String normalizedId = reviewRuleConfigPolicy.normalizeRuleId(id);
        ensureRegistered(normalizedId);
        ReviewRuleConfig rule = loadReviewRule(normalizedId);
        rule.setStatus(reviewRuleConfigPolicy.normalizeStatus(status));
        rule.setUpdatedAt(LocalDateTime.now());
        reviewRuleConfigMapper.updateById(rule);
        evictRuleCaches();
        return toReviewRuleDto(rule, loadRuleHitCounts().getOrDefault(rule.getId(), 0L));
    }

    private void evictRuleCaches() {
        cacheEvictionService.evictReviewRules();
        cacheEvictionService.evictDashboardRules();
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

    private ReviewRuleConfigDto toReviewRuleDto(ReviewRuleConfig rule, long hitCount) {
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
            lower(rule.getEnforcementMode())
        );
    }

    private void ensureRegistered(String id) {
        if (!reviewRuleRegistry.contains(id)) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Review rule has no registered detector implementation: " + id
            );
        }
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
}
