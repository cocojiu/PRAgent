package com.repoguard.agent.review.quality;

import com.repoguard.agent.mapper.ReviewQualityBaselineMapper;
import com.repoguard.agent.mapper.projection.ReviewQualityBaselineProjections.Execution;
import com.repoguard.agent.mapper.projection.ReviewQualityBaselineProjections.Group;
import com.repoguard.agent.mapper.projection.ReviewQualityBaselineProjections.Summary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ReviewQualityBaselineService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO_PERCENT = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    private final ReviewQualityBaselineMapper baselineMapper;

    public ReviewQualityBaselineService(ReviewQualityBaselineMapper baselineMapper) {
        this.baselineMapper = Objects.requireNonNull(baselineMapper, "baselineMapper");
    }

    public ReviewQualityBaseline loadBaseline() {
        Summary summary = baselineMapper.selectSummary();
        Execution execution = baselineMapper.selectExecution();
        List<Group> groups = baselineMapper.selectGroups();

        long totalFindings = count(summary == null ? null : summary.totalFindings());
        long highRiskFindings = count(summary == null ? null : summary.highRiskFindings());
        long labeledHighRiskFindings = count(summary == null ? null : summary.labeledHighRiskFindings());
        long confirmedHighRiskFindings = count(summary == null ? null : summary.confirmedHighRiskFindings());
        long falsePositiveHighRiskFindings = count(summary == null ? null : summary.falsePositiveHighRiskFindings());
        long anchoredFindings = count(summary == null ? null : summary.anchoredFindings());
        long duplicateFindings = count(summary == null ? null : summary.duplicateFindings());

        return new ReviewQualityBaseline(
            totalFindings,
            highRiskFindings,
            percentage(highRiskFindings, totalFindings),
            labeledHighRiskFindings,
            confirmedHighRiskFindings,
            falsePositiveHighRiskFindings,
            percentage(confirmedHighRiskFindings, labeledHighRiskFindings),
            percentage(falsePositiveHighRiskFindings, labeledHighRiskFindings),
            anchoredFindings,
            percentage(anchoredFindings, totalFindings),
            duplicateFindings,
            percentage(duplicateFindings, totalFindings),
            count(execution == null ? null : execution.completedTasks()),
            decimal(execution == null ? null : execution.averageDurationSeconds()),
            decimal(execution == null ? null : execution.totalLlmEstimatedCost()),
            groups == null ? List.of() : groups.stream().map(this::toBaseline).toList()
        );
    }

    private ReviewQualityGroupBaseline toBaseline(Group group) {
        long total = count(group.totalFindings());
        long confirmed = count(group.confirmedValidCount());
        long falsePositives = count(group.falsePositiveCount());
        long labeled = count(group.labeledCount());
        long highRisk = count(group.highRiskCount());
        long blocking = count(group.blockingCount());
        long anchored = count(group.anchoredCount());
        long duplicates = count(group.duplicateCount());
        BigDecimal precision = percentage(confirmed, labeled);
        BigDecimal falsePositiveRate = percentage(falsePositives, labeled);
        BigDecimal anchorRate = percentage(anchored, total);
        BigDecimal duplicateRate = percentage(duplicates, total);
        List<String> alerts = thresholdAlerts(labeled, precision, falsePositiveRate, anchorRate, duplicateRate);
        return new ReviewQualityGroupBaseline(
            group.ruleId(),
            group.source(),
            group.repository(),
            group.language(),
            group.severity(),
            group.versionKey(),
            group.detectorVersion(),
            count(group.ruleConfigVersion()),
            count(group.policyVersion()),
            group.promptVersion(),
            group.contextVersion(),
            group.schemaVersion(),
            group.verifierVersion(),
            group.aggregationVersion(),
            total,
            labeled,
            percentage(labeled, total),
            confirmed,
            falsePositives,
            count(group.pendingCount()),
            precision,
            falsePositiveRate,
            highRisk,
            percentage(highRisk, total),
            blocking,
            percentage(blocking, total),
            count(group.revokedBlockingCount()),
            anchored,
            anchorRate,
            duplicates,
            duplicateRate,
            labeled < 30 ? "INSUFFICIENT_SAMPLE" : alerts.isEmpty() ? "PASS" : "ALERT",
            alerts
        );
    }

    private List<String> thresholdAlerts(
        long labeled,
        BigDecimal precision,
        BigDecimal falsePositiveRate,
        BigDecimal anchorRate,
        BigDecimal duplicateRate
    ) {
        if (labeled < 30) {
            return List.of();
        }
        List<String> alerts = new ArrayList<>();
        if (precision.compareTo(BigDecimal.valueOf(90)) < 0) {
            alerts.add("precision_below_90");
        }
        if (falsePositiveRate.compareTo(BigDecimal.valueOf(10)) > 0) {
            alerts.add("false_positive_rate_above_10");
        }
        if (anchorRate.compareTo(BigDecimal.valueOf(95)) < 0) {
            alerts.add("anchor_rate_below_95");
        }
        if (duplicateRate.compareTo(BigDecimal.valueOf(5)) > 0) {
            alerts.add("duplicate_rate_above_5");
        }
        return List.copyOf(alerts);
    }

    private long count(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal decimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return ZERO_PERCENT;
        }
        return BigDecimal.valueOf(numerator)
            .multiply(ONE_HUNDRED)
            .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
}
