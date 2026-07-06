package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.ReviewRuleFeedbackStat;
import com.repoguard.agent.dto.ReviewRuleMetricDto;
import com.repoguard.agent.entity.ReviewRuleConfig;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReviewRuleMetricAssembler {

    public List<ReviewRuleMetricDto> buildRuleMetrics(List<ReviewRuleConfig> rules, ReviewRuleFeedbackStat feedbackStat) {
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
}
