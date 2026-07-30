package com.repoguard.agent.review.config;

import com.repoguard.agent.dto.ReviewRuleFeedbackStat;
import com.repoguard.agent.dto.ReviewRuleMetricDto;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.review.quality.ReviewQualityBaseline;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReviewRuleMetricAssembler {

    public List<ReviewRuleMetricDto> buildRuleMetrics(
        List<ReviewRuleConfig> rules,
        ReviewRuleFeedbackStat feedbackStat,
        ReviewQualityBaseline baseline
    ) {
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
            new ReviewRuleMetricDto("误报率", percentage(falsePositiveCount, reviewedCount), "人工判定误报 / 已判定", "red"),
            new ReviewRuleMetricDto(
                "高危占比",
                percentage(baseline.highRiskRate()),
                baseline.highRiskFindings() + " / " + baseline.totalFindings() + " 条 Finding",
                "orange"
            ),
            new ReviewRuleMetricDto(
                "高危精确率",
                percentage(baseline.labeledHighRiskPrecision()),
                baseline.confirmedHighRiskFindings() + " / " + baseline.labeledHighRiskFindings() + " 条明确标注",
                "green"
            ),
            new ReviewRuleMetricDto(
                "高危误报率",
                percentage(baseline.labeledHighRiskFalsePositiveRate()),
                baseline.falsePositiveHighRiskFindings() + " / " + baseline.labeledHighRiskFindings() + " 条明确标注",
                "red"
            ),
            new ReviewRuleMetricDto(
                "证据锚定率",
                percentage(baseline.anchorRate()),
                baseline.anchoredFindings() + " 条具备有效行号",
                "green"
            ),
            new ReviewRuleMetricDto(
                "精确重复率",
                percentage(baseline.duplicateRate()),
                baseline.duplicateFindings() + " 条重复 Finding",
                "red"
            ),
            new ReviewRuleMetricDto(
                "平均审查耗时",
                decimal(baseline.averageDurationSeconds()) + "s",
                baseline.completedTasks() + " 个已结束任务",
                "blue"
            ),
            new ReviewRuleMetricDto(
                "累计 LLM 成本",
                "$" + decimal(baseline.totalLlmEstimatedCost()),
                "历史已结束任务",
                "orange"
            )
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

    private String percentage(BigDecimal value) {
        return decimal(value) + "%";
    }

    private String decimal(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private boolean isHighSeverity(String severity) {
        return "HIGH".equals(severity) || "CRITICAL".equals(severity);
    }
}
