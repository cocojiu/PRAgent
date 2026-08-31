package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void operationalMetricsExposeCommentOutcomesPercentilesAndContributions() {
        LlmEvaluationReport report = LlmQualityEvaluator.evaluate(version, List.of(
            observation("first", 100, true, true, true, null, 2, 1, 1),
            observation("second", 200, false, true, false, true, 1, 2, 1),
            observation("third", 300, null, false, null, null, 0, 1, 0),
            observation("fourth", 400, true, true, true, false, 1, 1, 2)
        ), 1);

        assertThat(report.metrics().labeledComments()).isEqualTo(3);
        assertThat(report.metrics().usefulComments()).isEqualTo(2);
        assertThat(report.metrics().falsePositiveComments()).isEqualTo(1);
        assertThat(report.metrics().publishAttempts()).isEqualTo(3);
        assertThat(report.metrics().publishedComments()).isEqualTo(2);
        assertThat(report.metrics().fixedComments()).isEqualTo(1);
        assertThat(report.metrics().ignoredComments()).isEqualTo(1);
        assertThat(report.metrics().usefulCommentRate()).isEqualByComparingTo("0.6667");
        assertThat(report.metrics().publishSuccessRate()).isEqualByComparingTo("0.6667");
        assertThat(report.metrics().fixRate()).isEqualByComparingTo("0.5000");
        assertThat(report.metrics().ignoredRate()).isEqualByComparingTo("0.5000");
        assertThat(report.metrics().p50LatencyMs()).isEqualTo(200);
        assertThat(report.metrics().p95LatencyMs()).isEqualTo(400);
        assertThat(report.metrics().averageLatencyMs()).isEqualByComparingTo("250.0000");
        assertThat(report.metrics().averageTokensPerSample()).isEqualByComparingTo("1000.0000");
        assertThat(report.metrics().ruleContributionRate()).isEqualByComparingTo("0.3077");
        assertThat(report.metrics().llmContributionRate()).isEqualByComparingTo("0.3846");
        assertThat(report.metrics().verifiedContributionRate()).isEqualByComparingTo("0.3077");
    }

    @Test
    void explicitRealDatasetBlocksSyntheticOrIncompleteReproducibilityMetadata() {
        LlmEvaluationDatasetMetadata dataset = LlmEvaluationDatasetMetadata.synthetic(
            "offline-fixtures",
            "v1",
            30
        );

        LlmEvaluationReport report = LlmQualityEvaluator.evaluate(
            version,
            dataset,
            List.of(sample("one", false, "NONE", false, "NONE", true, "", true)),
            1
        );

        assertThat(report.qualityGatePassed()).isFalse();
        assertThat(report.eligible()).isFalse();
        assertThat(report.blockers()).contains(
            "DATASET_NOT_REAL_PR",
            "DATASET_SAMPLES_BELOW_50",
            "DATASET_SAMPLE_COUNT_MISMATCH:1/30",
            "DATASET_AUTHORIZATION_MISSING",
            "DATASET_NOT_ANONYMIZED",
            "DATASET_NOT_HUMAN_REVIEWED",
            "DATASET_FINGERPRINT_MISSING",
            "DATASET_REPOSITORY_LABEL_MISSING:1",
            "DATASET_SPLIT_LABEL_MISSING:1",
            "DATASET_SAMPLE_CONTEXT_MISSING:1",
            "VERSION_METADATA_INCOMPLETE"
        );
    }

    @Test
    void authorizedRealDatasetWithFixedAndRollingSplitsCanPass() {
        LlmEvaluationVersion reproducibleVersion = new LlmEvaluationVersion(
            "openai",
            "gpt-test",
            "review-prompt-v2",
            "review-context-v2",
            "review-schema-v2",
            "chunk-v1",
            new BigDecimal("0.20"),
            "rules-v3",
            "abc123"
        );
        List<LlmEvaluationObservation> observations = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            observations.add(sample(
                "real-pr-" + i,
                i < 10,
                i < 10 ? "HIGH" : "NONE",
                i < 10,
                i < 10 ? "HIGH" : "NONE",
                true,
                i < 10 ? "finding-" + i : "",
                true,
                i < 40
                    ? LlmEvaluationObservation.EvaluationSplit.FIXED_REGRESSION
                    : LlmEvaluationObservation.EvaluationSplit.ROLLING_OBSERVATION,
                i < 25 ? "repo-a" : "repo-b",
                context(i, i < 10)
            ));
        }
        String fingerprint = LlmQualityEvaluator.evaluate(reproducibleVersion, observations)
            .sampleFingerprint();
        LlmEvaluationDatasetMetadata dataset = new LlmEvaluationDatasetMetadata(
            "real-pr-v1",
            "2026-09-01",
            LlmEvaluationDatasetMetadata.DatasetKind.REAL_PR,
            2,
            50,
            40,
            10,
            true,
            true,
            true,
            fingerprint
        );

        LlmEvaluationReport report = LlmQualityEvaluator.evaluate(
            reproducibleVersion,
            dataset,
            observations
        );

        assertThat(report.qualityGatePassed()).isTrue();
        assertThat(report.versionKey()).contains(
            "temperature=0.2",
            "rules=rules-v3",
            "commit=abc123"
        );
        assertThat(report.dataset()).isEqualTo(dataset);
        assertThat(report.sampleFingerprint()).hasSize(64);
    }

    @Test
    void explicitRealDatasetBlocksManifestFingerprintDrift() {
        LlmEvaluationVersion reproducibleVersion = new LlmEvaluationVersion(
            "openai",
            "gpt-test",
            "review-prompt-v2",
            "review-context-v2",
            "review-schema-v2",
            "chunk-v1",
            new BigDecimal("0.20"),
            "rules-v3",
            "abc123"
        );
        LlmEvaluationDatasetMetadata dataset = new LlmEvaluationDatasetMetadata(
            "real-pr-v1",
            "2026-09-01",
            LlmEvaluationDatasetMetadata.DatasetKind.REAL_PR,
            2,
            50,
            40,
            10,
            true,
            true,
            true,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        );
        List<LlmEvaluationObservation> observations = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            observations.add(sample(
                "fingerprint-drift-" + i,
                i < 10,
                i < 10 ? "HIGH" : "NONE",
                i < 10,
                i < 10 ? "HIGH" : "NONE",
                true,
                i < 10 ? "finding-" + i : "",
                true,
                i < 40
                    ? LlmEvaluationObservation.EvaluationSplit.FIXED_REGRESSION
                    : LlmEvaluationObservation.EvaluationSplit.ROLLING_OBSERVATION,
                i < 25 ? "repo-a" : "repo-b",
                context(i, i < 10)
            ));
        }

        LlmEvaluationReport report = LlmQualityEvaluator.evaluate(
            reproducibleVersion,
            dataset,
            observations
        );

        assertThat(report.qualityGatePassed()).isFalse();
        assertThat(report.blockers()).contains("DATASET_FINGERPRINT_MISMATCH");
    }

    @Test
    void explicitRealDatasetBlocksRepositoryDistributionDrift() {
        LlmEvaluationVersion reproducibleVersion = new LlmEvaluationVersion(
            "openai",
            "gpt-test",
            "review-prompt-v2",
            "review-context-v2",
            "review-schema-v2",
            "chunk-v1",
            new BigDecimal("0.20"),
            "rules-v3",
            "abc123"
        );
        List<LlmEvaluationObservation> observations = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            observations.add(sample(
                "repository-drift-" + i,
                i < 10,
                i < 10 ? "HIGH" : "NONE",
                i < 10,
                i < 10 ? "HIGH" : "NONE",
                true,
                i < 10 ? "finding-" + i : "",
                true,
                i < 40
                    ? LlmEvaluationObservation.EvaluationSplit.FIXED_REGRESSION
                    : LlmEvaluationObservation.EvaluationSplit.ROLLING_OBSERVATION,
                "repo-a",
                context(i, i < 10)
            ));
        }
        String fingerprint = LlmQualityEvaluator.evaluate(reproducibleVersion, observations)
            .sampleFingerprint();
        LlmEvaluationDatasetMetadata dataset = new LlmEvaluationDatasetMetadata(
            "real-pr-v1",
            "2026-09-01",
            LlmEvaluationDatasetMetadata.DatasetKind.REAL_PR,
            2,
            50,
            40,
            10,
            true,
            true,
            true,
            fingerprint
        );

        LlmEvaluationReport report = LlmQualityEvaluator.evaluate(
            reproducibleVersion,
            dataset,
            observations
        );

        assertThat(report.qualityGatePassed()).isFalse();
        assertThat(report.blockers()).contains("DATASET_REPOSITORY_COUNT_MISMATCH:1/2");
    }

    @Test
    void sourceRepositoryKeyMustNotContainRawRepositoryIdentifiers() {
        assertThatThrownBy(() -> sample(
            "raw-repository-key",
            false,
            "NONE",
            false,
            "NONE",
            true,
            "",
            true,
            LlmEvaluationObservation.EvaluationSplit.FIXED_REGRESSION,
            "owner/repository"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sourceRepositoryKey");
    }

    private LlmEvaluationObservation observation(
        String id,
        long latencyMs,
        Boolean useful,
        boolean publishAttempted,
        Boolean published,
        Boolean ignored,
        long ruleFindings,
        long llmFindings,
        long verifiedFindings
    ) {
        return new LlmEvaluationObservation(
            id,
            "security",
            true,
            "HIGH",
            true,
            "HIGH",
            true,
            id,
            true,
            latencyMs,
            1_000,
            BigDecimal.valueOf(0.10),
            useful,
            publishAttempted,
            published,
            published == null || !Boolean.TRUE.equals(published)
                ? null
                : ("first".equals(id) ? Boolean.TRUE : Boolean.FALSE),
            ignored,
            ruleFindings,
            llmFindings,
            verifiedFindings
        );
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
        return sample(
            id,
            expectedFinding,
            expectedSeverity,
            predictedFinding,
            predictedSeverity,
            anchored,
            predictionKey,
            parsed,
            LlmEvaluationObservation.EvaluationSplit.UNSPECIFIED
        );
    }

    private LlmEvaluationObservation sample(
        String id,
        boolean expectedFinding,
        String expectedSeverity,
        boolean predictedFinding,
        String predictedSeverity,
        boolean anchored,
        String predictionKey,
        boolean parsed,
        LlmEvaluationObservation.EvaluationSplit split
    ) {
        return sample(
            id,
            expectedFinding,
            expectedSeverity,
            predictedFinding,
            predictedSeverity,
            anchored,
            predictionKey,
            parsed,
            split,
            ""
        );
    }

    private LlmEvaluationObservation sample(
        String id,
        boolean expectedFinding,
        String expectedSeverity,
        boolean predictedFinding,
        String predictedSeverity,
        boolean anchored,
        String predictionKey,
        boolean parsed,
        LlmEvaluationObservation.EvaluationSplit split,
        String sourceRepositoryKey
    ) {
        return sample(
            id,
            expectedFinding,
            expectedSeverity,
            predictedFinding,
            predictedSeverity,
            anchored,
            predictionKey,
            parsed,
            split,
            sourceRepositoryKey,
            LlmEvaluationSampleContext.unknown()
        );
    }

    private LlmEvaluationObservation sample(
        String id,
        boolean expectedFinding,
        String expectedSeverity,
        boolean predictedFinding,
        String predictedSeverity,
        boolean anchored,
        String predictionKey,
        boolean parsed,
        LlmEvaluationObservation.EvaluationSplit split,
        String sourceRepositoryKey,
        LlmEvaluationSampleContext sampleContext
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
            BigDecimal.valueOf(0.10),
            null,
            false,
            null,
            null,
            null,
            0,
            0,
            0,
            split,
            sourceRepositoryKey,
            sampleContext
        );
    }

    private LlmEvaluationSampleContext context(int index, boolean expectedFinding) {
        return new LlmEvaluationSampleContext(
            index % 2 == 0 ? "java" : "typescript",
            1 + index % 4,
            10 + index,
            index % 2 == 0 ? "jvm" : "web",
            expectedFinding ? "location-" + index : ""
        );
    }
}
