package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmQualityEvaluatorTest {

    private final LlmEvaluationVersion version = new LlmEvaluationVersion(
        "openai",
        "gpt-test",
        "review-prompt-v2",
        "review-context-v2",
        "review-schema-v2",
        "chunk-v1"
    );

    @Test
    void reportIsComparableAndBlocksSmallOrUnsafeSamples() {
        LlmEvaluationReport report = LlmQualityEvaluator.evaluate(version, List.of(
            sample("safe", false, "NONE", false, "NONE", true, "", true),
            sample("real", true, "HIGH", true, "HIGH", true, "auth:10", true),
            sample("duplicate", true, "HIGH", true, "HIGH", true, "auth:10", true),
            sample("missed", true, "HIGH", false, "NONE", false, "", true),
            sample("parse-failure", false, "NONE", false, "NONE", true, "", false)
        ), 5);

        assertThat(report.versionKey()).contains("openai/gpt-test", "schema=review-schema-v2");
        assertThat(report.sampleFingerprint()).hasSize(64);
        assertThat(report.truePositives()).isEqualTo(2);
        assertThat(report.falseNegatives()).isEqualTo(1);
        assertThat(report.duplicateRate()).isEqualByComparingTo("0.5000");
        assertThat(report.parseFailureRate()).isEqualByComparingTo("0.2000");
        assertThat(report.severityConfusion()).containsKey("HIGH");
        assertThat(report.qualityGatePassed()).isFalse();
        assertThat(report.blockers()).contains("DUPLICATE_RATE_ABOVE_5", "PARSE_FAILURE_RATE_ABOVE_5");
    }

    @Test
    void thirtyCleanObservationsCanPassThePromotionGate() {
        List<LlmEvaluationObservation> observations = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            observations.add(sample(
                "case-" + i,
                i < 10,
                i < 10 ? "HIGH" : "NONE",
                i < 10,
                i < 10 ? "HIGH" : "NONE",
                true,
                i < 10 ? "finding-" + i : "",
                true
            ));
        }

        LlmEvaluationReport report = LlmQualityEvaluator.evaluate(version, observations);

        assertThat(report.eligible()).isTrue();
        assertThat(report.qualityGatePassed()).isTrue();
        assertThat(report.precision()).isEqualByComparingTo("1.0000");
        assertThat(report.recall()).isEqualByComparingTo("1.0000");
        assertThat(report.totalTokens()).isEqualTo(30_000L);
        assertThat(report.totalCost()).isEqualByComparingTo("3.00");
    }

    private LlmEvaluationObservation sample(
        String id,
        boolean expectedFinding,
        String expectedSeverity,
        boolean predictedFinding,
        String predictedSeverity,
        boolean anchored,
        String predictionKey,
        boolean parsed
    ) {
        return new LlmEvaluationObservation(
            id,
            "security",
            expectedFinding,
            expectedSeverity,
            predictedFinding,
            predictedSeverity,
            anchored,
            predictionKey,
            parsed,
            100,
            1_000,
            BigDecimal.valueOf(0.10)
        );
    }
}
