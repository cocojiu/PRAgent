package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRuleFeedbackStat;
import com.repoguard.agent.dto.ReviewRuleHitCount;
import com.repoguard.agent.dto.ReviewRuleMetricDto;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import com.repoguard.agent.service.ReviewRuleConfigService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.CacheEvict;
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

    public ReviewRuleConfigServiceImpl(
        ReviewRuleConfigMapper reviewRuleConfigMapper,
        ReviewFindingMapper reviewFindingMapper,
        CacheEvictionService cacheEvictionService
    ) {
        this.reviewRuleConfigMapper = reviewRuleConfigMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.cacheEvictionService = cacheEvictionService;
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
        List<ReviewRuleConfigDto> ruleDtos = rules.stream()
            .map(rule -> toReviewRuleDto(rule, hitCountByRule.getOrDefault(rule.getId(), 0L)))
            .toList();
        return new ReviewRulesResponse(buildRuleMetrics(rules, feedbackStat), ruleDtos);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheNames.REVIEW_RULES, allEntries = true)
    public ReviewRuleConfigDto createReviewRule(ReviewRuleConfigRequest request) {
        String id = normalizeRuleId(request.id());
        if (reviewRuleConfigMapper.selectById(id) != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review rule already exists: " + id);
        }
        ReviewRuleConfig rule = new ReviewRuleConfig();
        applyReviewRuleRequest(rule, id, request);
        rule.setSortOrder(nextRuleSortOrder());
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        reviewRuleConfigMapper.insert(rule);
        evictDashboardOverview();
        return toReviewRuleDto(rule, 0);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheNames.REVIEW_RULES, allEntries = true)
    public ReviewRuleConfigDto updateReviewRule(String id, ReviewRuleConfigRequest request) {
        String normalizedId = normalizeRuleId(id);
        if (!normalizedId.equals(normalizeRuleId(request.id()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review rule id in path and body must match");
        }
        ReviewRuleConfig rule = loadReviewRule(normalizedId);
        applyReviewRuleRequest(rule, normalizedId, request);
        rule.setUpdatedAt(LocalDateTime.now());
        reviewRuleConfigMapper.updateById(rule);
        evictDashboardOverview();
        return toReviewRuleDto(rule, loadRuleHitCounts().getOrDefault(rule.getId(), 0L));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheNames.REVIEW_RULES, allEntries = true)
    public ReviewRuleConfigDto updateReviewRuleStatus(String id, String status) {
        ReviewRuleConfig rule = loadReviewRule(normalizeRuleId(id));
        rule.setStatus(normalizeStatus(status));
        rule.setUpdatedAt(LocalDateTime.now());
        reviewRuleConfigMapper.updateById(rule);
        evictDashboardOverview();
        return toReviewRuleDto(rule, loadRuleHitCounts().getOrDefault(rule.getId(), 0L));
    }

    private void evictDashboardOverview() {
        if (cacheEvictionService != null) {
            cacheEvictionService.evictDashboardOverview();
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
        rule.setSeverity(normalizeSeverity(request.severity()));
        rule.setStatus(normalizeStatus(request.status()));
        rule.setConfidence(request.confidence() == null ? 90 : request.confidence());
        rule.setDescription(request.description().trim());
        rule.setPositiveExample(cleanOptional(request.positiveExample()));
        rule.setFalsePositiveGuidance(cleanOptional(request.falsePositiveGuidance()));
    }

    private int nextRuleSortOrder() {
        List<ReviewRuleConfig> rules = reviewRuleConfigMapper.selectList(
            new LambdaQueryWrapper<ReviewRuleConfig>().orderByDesc(ReviewRuleConfig::getSortOrder)
        );
        return rules == null || rules.isEmpty() || rules.getFirst().getSortOrder() == null
            ? 10
            : rules.getFirst().getSortOrder() + 10;
    }

    private Map<String, Long> loadRuleHitCounts() {
        return buildRuleHitCounts(reviewFindingMapper.selectReviewRuleHitCounts());
    }

    private ReviewRuleFeedbackStat loadRuleFeedbackStat() {
        ReviewRuleFeedbackStat feedbackStat = reviewFindingMapper.selectReviewRuleFeedbackStat();
        return feedbackStat == null ? new ReviewRuleFeedbackStat() : feedbackStat;
    }

    private Map<String, Long> buildRuleHitCounts(List<ReviewRuleHitCount> hitCounts) {
        if (hitCounts == null || hitCounts.isEmpty()) {
            return Map.of();
        }
        return hitCounts.stream()
            .filter(count -> count != null && StringUtils.hasText(count.getRuleId()))
            .collect(Collectors.toMap(ReviewRuleHitCount::getRuleId, count -> safeCount(count.getTotal()), Long::sum));
    }

    private List<ReviewRuleMetricDto> buildRuleMetrics(List<ReviewRuleConfig> rules, ReviewRuleFeedbackStat feedbackStat) {
        long enabledCount = rules.stream().filter(rule -> "ENABLED".equals(rule.getStatus())).count();
        long highRiskCount = rules.stream().filter(rule -> isHighSeverity(rule.getSeverity())).count();
        long totalHits = safeCount(feedbackStat.getTotalHits());
        long validCount = safeCount(feedbackStat.getValidCount());
        long falsePositiveCount = safeCount(feedbackStat.getFalsePositiveCount());
        long reviewedCount = safeCount(feedbackStat.getReviewedCount());
        int averageConfidence = rules.isEmpty()
            ? 0
            : (int) Math.round(rules.stream().mapToInt(rule -> rule.getConfidence() == null ? 0 : rule.getConfidence()).average().orElse(0));
        return List.of(
            new ReviewRuleMetricDto("启用规则", String.valueOf(enabledCount), "共 " + rules.size() + " 条规则", "blue"),
            new ReviewRuleMetricDto("高风险规则", String.valueOf(highRiskCount), "包含 high / critical", "red"),
            new ReviewRuleMetricDto("累计命中", String.valueOf(totalHits), "来自历史审查结果", "orange"),
            new ReviewRuleMetricDto("平均置信度", averageConfidence + "%", "规则配置均值", "green"),
            new ReviewRuleMetricDto("有效率", percentage(validCount, reviewedCount), "人工判定有效 / 已判定", "green"),
            new ReviewRuleMetricDto("误报率", percentage(falsePositiveCount, reviewedCount), "人工判定误报 / 已判定", "red")
        );
    }

    private long safeCount(Long value) {
        return value == null ? 0L : value;
    }

    private String percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return "0%";
        }
        return Math.round(numerator * 100.0 / denominator) + "%";
    }

    private boolean isHighSeverity(String severity) {
        return "HIGH".equals(severity) || "CRITICAL".equals(severity);
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
            defaultString(rule.getFalsePositiveGuidance())
        );
    }

    private String cleanOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String normalizeRuleId(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSeverity(String value) {
        return value == null ? "INFO" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeStatus(String value) {
        return value == null ? "DISABLED" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }
}
