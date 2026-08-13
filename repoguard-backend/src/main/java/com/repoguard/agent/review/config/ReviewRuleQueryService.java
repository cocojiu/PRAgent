package com.repoguard.agent.review.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ReviewQualityGroupDto;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewRuleQueryService {

    private final ReviewRuleConfigMapper reviewRuleConfigMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewRuleConfigPolicy reviewRuleConfigPolicy;
    private final ReviewRuleMetricAssembler reviewRuleMetricAssembler;
    private final ReviewQualityBaselineService reviewQualityBaselineService;
    private final ReviewRuleRegistry reviewRuleRegistry;
    private final ReviewRuleResponseAssembler responseAssembler;
    private final ReviewStrategyPolicyService strategyPolicyService;

    public ReviewRuleQueryService(
        ReviewRuleConfigMapper reviewRuleConfigMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewRuleConfigPolicy reviewRuleConfigPolicy,
        ReviewRuleMetricAssembler reviewRuleMetricAssembler,
        ReviewQualityBaselineService reviewQualityBaselineService,
        ReviewRuleRegistry reviewRuleRegistry,
        ReviewRuleResponseAssembler responseAssembler,
        ReviewStrategyPolicyService strategyPolicyService
    ) {
        this.reviewRuleConfigMapper = Objects.requireNonNull(reviewRuleConfigMapper, "reviewRuleConfigMapper");
        this.reviewFindingMapper = Objects.requireNonNull(reviewFindingMapper, "reviewFindingMapper");
        this.reviewRuleConfigPolicy = Objects.requireNonNull(reviewRuleConfigPolicy, "reviewRuleConfigPolicy");
        this.reviewRuleMetricAssembler = Objects.requireNonNull(reviewRuleMetricAssembler, "reviewRuleMetricAssembler");
        this.reviewQualityBaselineService = Objects.requireNonNull(
            reviewQualityBaselineService,
            "reviewQualityBaselineService"
        );
        this.reviewRuleRegistry = Objects.requireNonNull(reviewRuleRegistry, "reviewRuleRegistry");
        this.responseAssembler = Objects.requireNonNull(responseAssembler, "responseAssembler");
        this.strategyPolicyService = Objects.requireNonNull(strategyPolicyService, "strategyPolicyService");
    }

    ReviewRulesResponse getReviewRules() {
        List<ReviewRuleConfig> rules = reviewRuleConfigMapper.selectList(
            new LambdaQueryWrapper<ReviewRuleConfig>()
                .orderByAsc(ReviewRuleConfig::getSortOrder)
                .orderByAsc(ReviewRuleConfig::getId)
        );
        Map<String, Long> hitCountByRule = loadRuleHitCounts();
        ReviewRuleFeedbackStat feedbackStat = loadRuleFeedbackStat();
        ReviewQualityBaseline qualityBaseline = loadBaseline();
        List<ReviewRuleConfigDto> ruleDtos = rules.stream()
            .filter(rule -> reviewRuleRegistry.contains(rule.getId()))
            .map(rule -> responseAssembler.toRuleDto(
                rule,
                hitCountByRule.getOrDefault(rule.getId(), 0L),
                qualityBaseline
            ))
            .toList();
        return new ReviewRulesResponse(
            reviewRuleMetricAssembler.buildRuleMetrics(rules, feedbackStat, qualityBaseline),
            ruleDtos,
            qualityBaseline.groups().stream().map(ReviewQualityGroupDto::from).toList(),
            strategyPolicyService.getActive(qualityBaseline)
        );
    }

    String normalizeRegisteredRuleId(String id) {
        String normalizedId = reviewRuleConfigPolicy.normalizeRuleId(id);
        ensureRegistered(normalizedId);
        return normalizedId;
    }

    ReviewRuleConfig loadRule(String normalizedId) {
        ReviewRuleConfig rule = reviewRuleConfigMapper.selectById(normalizedId);
        if (rule == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review rule not found: " + normalizedId);
        }
        return rule;
    }

    ReviewQualityBaseline loadBaseline() {
        return reviewQualityBaselineService.loadBaseline();
    }

    String detectorVersion(String ruleId) {
        return reviewRuleRegistry.detectorVersion(ruleId);
    }

    ReviewRuleConfigDto toRuleDto(ReviewRuleConfig rule, ReviewQualityBaseline baseline) {
        return responseAssembler.toRuleDto(
            rule,
            loadRuleHitCounts().getOrDefault(rule.getId(), 0L),
            baseline
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

    private Map<String, Long> loadRuleHitCounts() {
        List<ReviewRuleHitCount> hitCounts = ReviewFindingProjectionAssembler.toRuleHitDtos(
            reviewFindingMapper.selectReviewRuleHitCounts()
        );
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

    private ReviewRuleFeedbackStat loadRuleFeedbackStat() {
        ReviewRuleFeedbackStat feedbackStat = ReviewFindingProjectionAssembler.toDto(
            reviewFindingMapper.selectReviewRuleFeedbackStat()
        );
        return feedbackStat == null ? new ReviewRuleFeedbackStat() : feedbackStat;
    }
}
