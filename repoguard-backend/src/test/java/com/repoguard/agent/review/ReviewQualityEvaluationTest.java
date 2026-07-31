package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.review.ReviewRuleProvider;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ReviewQualityEvaluationTest {

    private final ReviewRuleProvider reviewRuleProvider = org.mockito.Mockito.mock(ReviewRuleProvider.class);
    private final ReviewQualityEvaluationRunner runner;

    ReviewQualityEvaluationTest() {
        when(reviewRuleProvider.getRulesById()).thenReturn(ReviewRuleTestFixtures.defaultSettings());
        runner = new ReviewQualityEvaluationRunner(
            new ObjectMapper(),
            ReviewRuleTestFixtures.defaultReviewer(reviewRuleProvider)
        );
    }

    @Test
    void datasetProvidesTwentyTwoRepresentativeOfflineCases() {
        var cases = runner.loadCases();

        assertThat(cases).hasSize(22);
        assertThat(cases)
            .extracting(ReviewQualityEvaluationRunner.EvaluationCase::category)
            .contains(
                "SECURITY",
                "STABILITY",
                "CLEAN_PR",
                "DATABASE_MIGRATION",
                "MESSAGE_QUEUE",
                "EXTERNAL_CALL",
                "ACCESS_CONTROL",
                "OBSERVABILITY",
                "GITHUB_WRITEBACK",
                "API_CONTRACT"
            );
        assertThat(cases)
            .allSatisfy(evaluationCase -> {
                assertThat(evaluationCase.id()).isNotBlank();
                assertThat(evaluationCase.description()).isNotBlank();
                assertThat(evaluationCase.files()).isNotEmpty();
                assertThat(evaluationCase.expected()).isNotNull();
            });
    }

    @TestFactory
    Stream<DynamicTest> evaluatesEveryCaseWithRepeatableAssertions() {
        return runner.loadCases().stream()
            .map(evaluationCase -> DynamicTest.dynamicTest(
                evaluationCase.id() + " - " + evaluationCase.description(),
                () -> assertOutcome(evaluationCase)
            ));
    }

    private void assertOutcome(ReviewQualityEvaluationRunner.EvaluationCase evaluationCase) {
        var actual = runner.evaluate(evaluationCase);
        var expected = evaluationCase.expected();

        assertThat(actual.riskLevel()).isEqualTo(expected.riskLevel());
        assertThat(actual.findings()).hasSize(expected.findingCount());
        assertThat(actual.findings())
            .extracting(ReviewFindingResult::ruleId)
            .filteredOn(java.util.Objects::nonNull)
            .containsExactlyInAnyOrderElementsOf(expected.ruleIds());
        assertThat(actual.findings())
            .extracting(ReviewFindingResult::source)
            .containsExactlyInAnyOrderElementsOf(expected.sources());
        assertThat(Set.copyOf(actual.chunkReasons()))
            .containsAll(expected.chunkReasons());
        assertThat(actual.findings())
            .filteredOn(finding -> "RULE".equals(finding.source()) && "HIGH".equals(finding.severity()))
            .allSatisfy(finding -> {
                assertThat(finding.confidence()).isEqualTo("HIGH");
                assertThat(finding.isBlocking()).isTrue();
                assertThat(finding.evidence()).isNotBlank();
                assertThat(finding.impact()).isNotBlank();
                assertThat(finding.fixExample()).isNotBlank();
                assertThat(finding.reviewDimension()).isNotBlank();
            });
    }
}
