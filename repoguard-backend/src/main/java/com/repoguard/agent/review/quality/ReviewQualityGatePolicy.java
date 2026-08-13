package com.repoguard.agent.review.quality;

import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReviewQualityGatePolicy {

    public static final int MIN_BLOCK_SAMPLES = 30;
    private static final double WILSON_Z_95 = 1.959963984540054d;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MIN_PRECISION_LOWER_BOUND = BigDecimal.valueOf(90);

    public ReviewRuleQualityGateDto evaluate(List<ReviewQualityGroupBaseline> groups) {
        List<ReviewQualityGroupBaseline> matching = groups == null ? List.of() : groups;
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
        Assessment assessment = assess(
            highRiskLabeled,
            confirmed,
            falsePositives,
            anchorRate,
            duplicateRate
        );
        return new ReviewRuleQualityGateDto(
            labeled,
            highRiskLabeled,
            precision,
            falsePositiveRate,
            anchorRate,
            duplicateRate,
            labeled > 0,
            assessment.blockEligible(),
            assessment.status(),
            assessment.blockers()
        );
    }

    public Assessment assess(
        long samples,
        long confirmed,
        long falsePositives,
        BigDecimal anchorRate,
        BigDecimal duplicateRate
    ) {
        List<String> blockers = blockers(
            samples,
            confirmed,
            falsePositives,
            anchorRate,
            duplicateRate
        );
        boolean blockEligible = blockers.isEmpty();
        String status = samples < MIN_BLOCK_SAMPLES
            ? "INSUFFICIENT_SAMPLE"
            : blockEligible ? "PASS" : "ALERT";
        return new Assessment(blockEligible, status, blockers);
    }

    public BigDecimal precisionLowerBound(long confirmed, long samples) {
        return precisionLowerBoundRaw(confirmed, samples).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal precisionLowerBoundRaw(long confirmed, long samples) {
        if (samples <= 0) {
            return BigDecimal.ZERO;
        }
        long boundedConfirmed = Math.max(0, Math.min(confirmed, samples));
        double sampleCount = samples;
        double proportion = (double) boundedConfirmed / sampleCount;
        double zSquared = WILSON_Z_95 * WILSON_Z_95;
        double denominator = 1.0d + zSquared / sampleCount;
        double center = proportion + zSquared / (2.0d * sampleCount);
        double margin = WILSON_Z_95 * Math.sqrt(
            proportion * (1.0d - proportion) / sampleCount
                + zSquared / (4.0d * sampleCount * sampleCount)
        );
        double lowerBound = Math.max(0.0d, (center - margin) / denominator);
        return BigDecimal.valueOf(lowerBound * 100.0d);
    }

    private List<String> blockers(
        long samples,
        long confirmed,
        long falsePositives,
        BigDecimal anchorRate,
        BigDecimal duplicateRate
    ) {
        List<String> blockers = new ArrayList<>();
        if (samples < MIN_BLOCK_SAMPLES) {
            blockers.add("labeled_high_risk_samples_below_30");
            return List.copyOf(blockers);
        }
        if (confirmed + falsePositives != samples) {
            blockers.add("labeled_outcome_counts_inconsistent");
        }
        if (precisionLowerBoundRaw(confirmed, samples).compareTo(MIN_PRECISION_LOWER_BOUND) < 0) {
            blockers.add("precision_wilson_lower_bound_below_90");
        }
        if (anchorRate.compareTo(BigDecimal.valueOf(95)) < 0) {
            blockers.add("anchor_rate_below_95");
        }
        if (duplicateRate.compareTo(BigDecimal.valueOf(5)) > 0) {
            blockers.add("duplicate_rate_above_5");
        }
        return List.copyOf(blockers);
    }

    private BigDecimal percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        }
        return BigDecimal.valueOf(numerator)
            .multiply(ONE_HUNDRED)
            .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    public record Assessment(
        boolean blockEligible,
        String status,
        List<String> blockers
    ) {
        public Assessment {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }
}
