package com.repoguard.agent.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Service
public class ReviewRuleProvider {

    private final ReviewRuleConfigMapper reviewRuleConfigMapper;
    private final ReviewRuleRegistry reviewRuleRegistry;
    private final MeterRegistry meterRegistry;

    public ReviewRuleProvider(
        ReviewRuleConfigMapper reviewRuleConfigMapper,
        ReviewRuleRegistry reviewRuleRegistry,
        MeterRegistry meterRegistry
    ) {
        this.reviewRuleConfigMapper = Objects.requireNonNull(reviewRuleConfigMapper, "reviewRuleConfigMapper");
        this.reviewRuleRegistry = Objects.requireNonNull(reviewRuleRegistry, "reviewRuleRegistry");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    public Map<String, ReviewRuleSettings> getRulesById() {
        List<ReviewRuleConfig> rules = reviewRuleConfigMapper.selectList(
            new LambdaQueryWrapper<ReviewRuleConfig>()
                .orderByAsc(ReviewRuleConfig::getSortOrder)
                .orderByAsc(ReviewRuleConfig::getId)
        );
        if (CollectionUtils.isEmpty(rules)) {
            reviewRuleRegistry.ruleIds().forEach(this::recordMissingConfiguration);
            return Map.of();
        }
        Map<String, ReviewRuleSettings> rulesById = new LinkedHashMap<>();
        for (ReviewRuleConfig rule : rules) {
            if (rule == null || !StringUtils.hasText(rule.getId())) {
                continue;
            }
            ReviewRuleSettings settings = new ReviewRuleSettings(
                rule.getId(),
                rule.getStatus(),
                rule.getFilePatterns(),
                rule.getSeverity(),
                rule.getConfidence() == null ? -1 : rule.getConfidence(),
                EnforcementMode.from(rule.getEnforcementMode()),
                rule.getPositiveExample(),
                rule.getFalsePositiveGuidance()
            );
            if (!settings.disabled() && !reviewRuleRegistry.contains(settings.id())) {
                meterRegistry.counter(
                    "repoguard.review.rule.configuration_error",
                    "reason",
                    "detector_missing",
                    "rule_id",
                    settings.id()
                ).increment();
                throw new IllegalStateException(
                    "Enabled review rule has no registered detector implementation: " + settings.id()
                );
            }
            if (rulesById.putIfAbsent(settings.id(), settings) != null) {
                throw new IllegalStateException("Duplicate review rule configuration id: " + settings.id());
            }
        }
        reviewRuleRegistry.ruleIds().stream()
            .filter(ruleId -> !rulesById.containsKey(ruleId))
            .forEach(this::recordMissingConfiguration);
        return Collections.unmodifiableMap(new LinkedHashMap<>(rulesById));
    }

    private void recordMissingConfiguration(String ruleId) {
        meterRegistry.counter(
            "repoguard.review.rule.configuration_missing",
            "rule_id",
            ruleId
        ).increment();
    }
}
