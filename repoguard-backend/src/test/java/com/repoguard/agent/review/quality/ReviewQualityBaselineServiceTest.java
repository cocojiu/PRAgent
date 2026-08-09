package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.repoguard.agent.mapper.ReviewQualityBaselineMapper;
import com.repoguard.agent.mapper.projection.ReviewQualityBaselineProjections.Execution;
import com.repoguard.agent.mapper.projection.ReviewQualityBaselineProjections.Group;
import com.repoguard.agent.mapper.projection.ReviewQualityBaselineProjections.Summary;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewQualityBaselineServiceTest {

    private final ReviewQualityBaselineMapper mapper = org.mockito.Mockito.mock(ReviewQualityBaselineMapper.class);
    private final ReviewQualityBaselineService service = new ReviewQualityBaselineService(
        mapper,
        new ReviewQualityGatePolicy()
    );

    @Test
    void computesComparableBaselineWithExplicitLabelDenominators() {
        when(mapper.selectSummary()).thenReturn(new Summary(20L, 10L, 8L, 7L, 1L, 18L, 2L));
        when(mapper.selectExecution()).thenReturn(new Execution(
            5L,
            new BigDecimal("12.40"),
            new BigDecimal("1.2345")
        ));
        when(mapper.selectGroups()).thenReturn(List.of(new Group(
            "RG-LOG-001",
            "RULE",
            "octocat/demo",
            "JAVA",
            "HIGH",
            10L,
            6L,
            2L,
            2L,
            9L
        )));

        ReviewQualityBaseline baseline = service.loadBaseline();

        assertThat(baseline.totalFindings()).isEqualTo(20);
        assertThat(baseline.highRiskRate()).isEqualByComparingTo("50.00");
        assertThat(baseline.labeledHighRiskPrecision()).isEqualByComparingTo("87.50");
        assertThat(baseline.labeledHighRiskFalsePositiveRate()).isEqualByComparingTo("12.50");
        assertThat(baseline.anchorRate()).isEqualByComparingTo("90.00");
        assertThat(baseline.duplicateRate()).isEqualByComparingTo("10.00");
        assertThat(baseline.completedTasks()).isEqualTo(5);
        assertThat(baseline.averageDurationSeconds()).isEqualByComparingTo("12.40");
        assertThat(baseline.totalLlmEstimatedCost()).isEqualByComparingTo("1.2345");
        assertThat(baseline.groups()).singleElement().satisfies(group -> {
            assertThat(group.ruleId()).isEqualTo("RG-LOG-001");
            assertThat(group.labeledPrecision()).isEqualByComparingTo("75.00");
            assertThat(group.labeledFalsePositiveRate()).isEqualByComparingTo("25.00");
            assertThat(group.anchorRate()).isEqualByComparingTo("90.00");
            assertThat(group.pendingCount()).isEqualTo(2);
            assertThat(group.policyVersion()).isEqualTo(1);
            assertThat(group.contextVersion()).isEqualTo("not-applicable");
            assertThat(group.thresholdStatus()).isEqualTo("INSUFFICIENT_SAMPLE");
            assertThat(group.thresholdAlerts()).containsExactly("labeled_high_risk_samples_below_30");
        });
    }

    @Test
    void reportsTheSharedGateBlockersAtEverySampleSize() {
        when(mapper.selectSummary()).thenReturn(null);
        when(mapper.selectExecution()).thenReturn(null);
        when(mapper.selectGroups()).thenReturn(List.of(
            group("under-30", 29L, 29L, 20L, 9L, 20L, 3L),
            group("at-30", 30L, 30L, 20L, 10L, 20L, 3L)
        ));

        ReviewQualityBaseline baseline = service.loadBaseline();

        assertThat(baseline.groups().get(0).thresholdStatus()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(baseline.groups().get(0).thresholdAlerts())
            .containsExactly("labeled_high_risk_samples_below_30");
        assertThat(baseline.groups().get(1).thresholdStatus()).isEqualTo("ALERT");
        assertThat(baseline.groups().get(1).thresholdAlerts()).containsExactly(
            "precision_wilson_lower_bound_below_90",
            "anchor_rate_below_95",
            "duplicate_rate_above_5"
        );
    }

    @Test
    void appliesTheSameWilsonThresholdAsLifecyclePromotion() {
        when(mapper.selectSummary()).thenReturn(null);
        when(mapper.selectExecution()).thenReturn(null);
        when(mapper.selectGroups()).thenReturn(List.of(
            group("point-estimate-pass", 30L, 30L, 27L, 3L, 30L, 0L)
        ));

        ReviewQualityGroupBaseline group = service.loadBaseline().groups().getFirst();

        assertThat(group.labeledPrecision()).isEqualByComparingTo("90.00");
        assertThat(group.labeledFalsePositiveRate()).isEqualByComparingTo("10.00");
        assertThat(group.thresholdStatus()).isEqualTo("ALERT");
        assertThat(group.thresholdAlerts()).containsExactly("precision_wilson_lower_bound_below_90");
    }

    @Test
    void returnsStableZeroBaselineWhenNoHistoryExists() {
        when(mapper.selectSummary()).thenReturn(null);
        when(mapper.selectExecution()).thenReturn(null);
        when(mapper.selectGroups()).thenReturn(null);

        ReviewQualityBaseline baseline = service.loadBaseline();

        assertThat(baseline.totalFindings()).isZero();
        assertThat(baseline.highRiskRate()).isEqualByComparingTo("0.00");
        assertThat(baseline.labeledHighRiskPrecision()).isEqualByComparingTo("0.00");
        assertThat(baseline.labeledHighRiskFalsePositiveRate()).isEqualByComparingTo("0.00");
        assertThat(baseline.anchorRate()).isEqualByComparingTo("0.00");
        assertThat(baseline.duplicateRate()).isEqualByComparingTo("0.00");
        assertThat(baseline.groups()).isEmpty();
    }

    private Group group(
        String versionKey,
        Long total,
        Long labeled,
        Long confirmed,
        Long falsePositives,
        Long anchored,
        Long duplicates
    ) {
        return new Group(
            "RG-JAVA-001",
            "RULE",
            "octocat/demo",
            "JAVA",
            "HIGH",
            versionKey,
            "rg-java-001-detector-v2",
            2L,
            5L,
            "not-applicable",
            "not-applicable",
            "not-applicable",
            "not-applicable",
            "server-risk-v2",
            total,
            labeled,
            confirmed,
            falsePositives,
            total - labeled,
            total,
            0L,
            0L,
            anchored,
            duplicates
        );
    }
}
