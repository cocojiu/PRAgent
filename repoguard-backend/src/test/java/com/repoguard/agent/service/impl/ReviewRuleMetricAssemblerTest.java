package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.ReviewRuleFeedbackStat;
import com.repoguard.agent.dto.ReviewRuleMetricDto;
import com.repoguard.agent.entity.ReviewRuleConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRuleMetricAssemblerTest {

    private final ReviewRuleMetricAssembler assembler = new ReviewRuleMetricAssembler();

    @Test
    void buildsRuleMetricsFromRulesAndFeedbackStats() {
        ReviewRuleFeedbackStat feedbackStat = feedbackStat(12L, 5L, 3L, 8L);

        List<ReviewRuleMetricDto> metrics = assembler.buildRuleMetrics(List.of(
            rule("ENABLED", "HIGH", 90),
            rule("ENABLED", "CRITICAL", 70),
            rule("DISABLED", "MEDIUM", null)
        ), feedbackStat);

        assertThat(metrics).extracting(ReviewRuleMetricDto::label)
            .containsExactly("启用规则", "高风险规则", "累计命中", "平均置信度", "有效率", "误报率");
        assertThat(metrics).extracting(ReviewRuleMetricDto::value)
            .containsExactly("2", "2", "12", "53%", "63%", "38%");
        assertThat(metrics.getFirst().note()).isEqualTo("共 3 条规则");
        assertThat(metrics).extracting(ReviewRuleMetricDto::color)
            .containsExactly("blue", "red", "orange", "green", "green", "red");
    }

    @Test
    void defaultsEmptyRulesAndMissingReviewedFeedbackToZeroMetrics() {
        ReviewRuleFeedbackStat feedbackStat = feedbackStat(null, null, null, 0L);

        List<ReviewRuleMetricDto> metrics = assembler.buildRuleMetrics(List.of(), feedbackStat);

        assertThat(metrics).extracting(ReviewRuleMetricDto::value)
            .containsExactly("0", "0", "0", "0%", "0%", "0%");
        assertThat(metrics.getFirst().note()).isEqualTo("共 0 条规则");
    }

    private ReviewRuleConfig rule(String status, String severity, Integer confidence) {
        ReviewRuleConfig rule = new ReviewRuleConfig();
        rule.setStatus(status);
        rule.setSeverity(severity);
        rule.setConfidence(confidence);
        return rule;
    }

    private ReviewRuleFeedbackStat feedbackStat(Long totalHits, Long validCount, Long falsePositiveCount, Long reviewedCount) {
        ReviewRuleFeedbackStat stat = new ReviewRuleFeedbackStat();
        stat.setTotalHits(totalHits);
        stat.setValidCount(validCount);
        stat.setFalsePositiveCount(falsePositiveCount);
        stat.setReviewedCount(reviewedCount);
        return stat;
    }
}
