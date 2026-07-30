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
    private final ReviewQualityBaselineService service = new ReviewQualityBaselineService(mapper);

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
        });
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
}
