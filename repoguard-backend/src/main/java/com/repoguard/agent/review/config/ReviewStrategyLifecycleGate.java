package com.repoguard.agent.review.config;

import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.review.ReviewStrategyRelease;
import com.repoguard.agent.review.quality.ReviewQualityGroupBaseline;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ReviewStrategyLifecycleGate {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public ReviewRuleQualityGateDto evaluate(
        ReviewStrategyRelease release,
        List<ReviewQualityGroupBaseline> groups
    ) {
        List<ReviewQualityGroupBaseline> matching = groups == null
            ? List.of()
            : groups.stream()
                .filter(group -> containsLlm(group.source()))
                .filter(group -> release.promptVersion().equals(group.promptVersion()))
                .filter(group -> release.contextVersion().equals(group.contextVersion()))
                .filter(group -> release.schemaVersion().equals(group.schemaVersion()))
                .filter(group -> release.verifierVersion().equals(group.verifierVersion()))
                .filter(group -> release.aggregationVersion().equals(group.aggregationVersion()))
                .toList();
        long labeled = matching.stream().mapToLong(ReviewQualityGroupBaseline::labeledCount).sum();
        List<ReviewQualityGroupBaseline> highRisk = matching.stream()
            .filter(group -> "HIGH".equalsIgnoreCase(group.severity())
                || "CRITICAL".equalsIgnoreCase(group.severity()))
            .toList();
        long highRiskLabeled = highRisk.stream().mapToLong(ReviewQualityGroupBaseline::labeledCount).sum();
        long confirmed = highRisk.stream().mapToLong(ReviewQualityGroupBaseline::confirmedValidCount).sum();
        long falsePositives = highRisk.stream().mapToLong(ReviewQualityGroupBaseline::falsePositiveCount).sum();
        long total = highRisk.stream().mapToLong(ReviewQualityGroupBaseline::totalFindings).sum();
        long anchored = highRisk.stream().mapToLong(ReviewQualityGroupBaseline::anchoredCount).sum();
        long duplicates = highRisk.stream().mapToLong(ReviewQualityGroupBaseline::duplicateCount).sum();
        BigDecimal precision = percentage(confirmed, highRiskLabeled);
        BigDecimal falsePositiveRate = percentage(falsePositives, highRiskLabeled);
        BigDecimal anchorRate = percentage(anchored, total);
        BigDecimal duplicateRate = percentage(duplicates, total);
        List<String> blockers = blockers(highRiskLabeled, precision, falsePositiveRate, anchorRate, duplicateRate);
        return new ReviewRuleQualityGateDto(
            labeled,
            highRiskLabeled,
            precision,
            falsePositiveRate,
            anchorRate,
            duplicateRate,
            labeled > 0,
            blockers.isEmpty(),
            highRiskLabeled < 30 ? "INSUFFICIENT_SAMPLE" : blockers.isEmpty() ? "PASS" : "ALERT",
            blockers
        );
    }

    private List<String> blockers(
        long samples,
        BigDecimal precision,
        BigDecimal falsePositiveRate,
        BigDecimal anchorRate,
        BigDecimal duplicateRate
    ) {
        List<String> blockers = new ArrayList<>();
        if (samples < 30) {
            blockers.add("labeled_high_risk_samples_below_30");
            return List.copyOf(blockers);
        }
        if (precision.compareTo(BigDecimal.valueOf(90)) < 0) blockers.add("precision_below_90");
        if (falsePositiveRate.compareTo(BigDecimal.valueOf(10)) > 0) blockers.add("false_positive_rate_above_10");
        if (anchorRate.compareTo(BigDecimal.valueOf(95)) < 0) blockers.add("anchor_rate_below_95");
        if (duplicateRate.compareTo(BigDecimal.valueOf(5)) > 0) blockers.add("duplicate_rate_above_5");
        return List.copyOf(blockers);
    }

    private boolean containsLlm(String source) {
        return source != null && source.toUpperCase(Locale.ROOT).contains("LLM");
    }

    private BigDecimal percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        }
        return BigDecimal.valueOf(numerator)
            .multiply(ONE_HUNDRED)
            .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
}
