package com.repoguard.agent.review.config;

import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.review.quality.ReviewQualityGroupBaseline;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ReviewRuleLifecycleGate {

    private static final int MIN_BLOCK_SAMPLES = 30;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    public ReviewRuleQualityGateDto evaluate(
        String ruleId,
        long configVersion,
        List<ReviewQualityGroupBaseline> groups
    ) {
        return evaluate(ruleId, null, configVersion, groups);
    }

    public ReviewRuleQualityGateDto evaluate(
        String ruleId,
        String detectorVersion,
        long configVersion,
        List<ReviewQualityGroupBaseline> groups
    ) {
        List<ReviewQualityGroupBaseline> matching = groups == null
            ? List.of()
            : groups.stream()
                .filter(group -> group.ruleConfigVersion() == configVersion)
                .filter(group -> containsComponent(group.ruleId(), ruleId))
                .filter(group -> detectorVersion == null || containsComponent(group.detectorVersion(), detectorVersion))
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
        List<String> blockers = new ArrayList<>();
        if (highRiskLabeled < MIN_BLOCK_SAMPLES) {
            blockers.add("labeled_high_risk_samples_below_30");
        } else {
            if (precision.compareTo(BigDecimal.valueOf(90)) < 0) {
                blockers.add("precision_below_90");
            }
            if (falsePositiveRate.compareTo(BigDecimal.valueOf(10)) > 0) {
                blockers.add("false_positive_rate_above_10");
            }
            if (anchorRate.compareTo(BigDecimal.valueOf(95)) < 0) {
                blockers.add("anchor_rate_below_95");
            }
            if (duplicateRate.compareTo(BigDecimal.valueOf(5)) > 0) {
                blockers.add("duplicate_rate_above_5");
            }
        }
        boolean commentEligible = labeled > 0;
        boolean blockEligible = blockers.isEmpty();
        return new ReviewRuleQualityGateDto(
            labeled,
            highRiskLabeled,
            precision,
            falsePositiveRate,
            anchorRate,
            duplicateRate,
            commentEligible,
            blockEligible,
            highRiskLabeled < MIN_BLOCK_SAMPLES ? "INSUFFICIENT_SAMPLE" : blockEligible ? "PASS" : "ALERT",
            List.copyOf(blockers)
        );
    }

    private boolean containsComponent(String compositeValue, String expectedValue) {
        if (compositeValue == null || expectedValue == null) {
            return false;
        }
        String expected = expectedValue.trim().toUpperCase(Locale.ROOT);
        for (String part : compositeValue.toUpperCase(Locale.ROOT).split("[+/]")) {
            if (expected.equals(part.trim())) {
                return true;
            }
        }
        return false;
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
