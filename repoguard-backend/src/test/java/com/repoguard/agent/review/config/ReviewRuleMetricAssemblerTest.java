package com.repoguard.agent.review.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.ReviewRuleFeedbackStat;
import com.repoguard.agent.dto.ReviewRuleMetricDto;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.review.quality.ReviewQualityBaseline;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRuleMetricAssemblerTest {

    private final ReviewRuleMetricAssembler assembler = new ReviewRuleMetricAssembler();

    @Test
    void buildsRuleMetricsFromRulesAndFeedbackStats() {
        ReviewRuleFeedbackStat feedbackStat = feedbackStat(12L, 5L, 3L, 8L);

        List<ReviewRuleMetricDto> metrics = assembler.buildRuleMetrics(
            List.of(
                rule("ENABLED", "HIGH", 90),
                rule("ENABLED", "CRITICAL", 70),
                rule("DISABLED", "MEDIUM", null)
            ),
            feedbackStat,
            qualityBaseline()
        );

        assertThat(metrics).extracting(ReviewRuleMetricDto::label)
            .containsExactly(
                "启用规则",
                "高风险规则",
                "累计命中",
                "平均置信度",
                "有效率",
                "误报率",
                "高危占比",
                "高危精确率",
                "高危误报率",
                "证据锚定率",
                "精确重复率",
                "平均审查耗时",
                "累计 LLM 成本"
            );
        assertThat(metrics).extracting(ReviewRuleMetricDto::value)
            .containsExactly(
                "2",
                "2",
                "12",
                "53%",
                "63%",
                "38%",
                "40%",
                "66.67%",
                "33.33%",
                "90%",
                "10%",
                "12.4s",
                "$1.2345"
            );
        assertThat(metrics.getFirst().note()).isEqualTo("共 3 条规则");
        assertThat(metrics).extracting(ReviewRuleMetricDto::color)
            .containsExactly(
                "blue",
                "red",
                "orange",
                "green",
                "green",
                "red",
                "orange",
                "green",
                "red",
                "green",
                "red",
                "blue",
                "orange"
            );
    }

    @Test
    void defaultsEmptyRulesAndMissingReviewedFeedbackToZeroMetrics() {
        ReviewRuleFeedbackStat feedbackStat = feedbackStat(null, null, null, 0L);

        List<ReviewRuleMetricDto> metrics = assembler.buildRuleMetrics(
            List.of(),
            feedbackStat,
            emptyQualityBaseline()
        );

        assertThat(metrics).extracting(ReviewRuleMetricDto::value)
            .containsExactly(
                "0",
                "0",
                "0",
                "0%",
                "0%",
                "0%",
                "0%",
                "0%",
                "0%",
                "0%",
                "0%",
                "0s",
                "$0"
            );
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

    private ReviewQualityBaseline qualityBaseline() {
        return new ReviewQualityBaseline(
            10,
            4,
            new BigDecimal("40.00"),
            3,
            2,
            1,
            new BigDecimal("66.67"),
            new BigDecimal("33.33"),
            9,
            new BigDecimal("90.00"),
            1,
            new BigDecimal("10.00"),
            5,
            new BigDecimal("12.40"),
            new BigDecimal("1.2345"),
            List.of()
        );
    }

    private ReviewQualityBaseline emptyQualityBaseline() {
        return new ReviewQualityBaseline(
            0,
            0,
            BigDecimal.ZERO,
            0,
            0,
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            0,
            BigDecimal.ZERO,
            0,
            BigDecimal.ZERO,
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of()
        );
    }
}
