package com.repoguard.agent.review.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Privacy and reproducibility manifest for a quality-evaluation dataset.
 * It stores only dataset metadata and fingerprints; source code and provider payloads stay out of
 * the repository and are supplied by an authorized local evaluation process.
 */
public record LlmEvaluationDatasetMetadata(
    String datasetId,
    String datasetVersion,
    DatasetKind kind,
    int sourceRepositoryCount,
    int sampleCount,
    int fixedRegressionSamples,
    int rollingObservationSamples,
    boolean authorized,
    boolean anonymized,
    boolean humanReviewed,
    String sampleFingerprint
) {

    public enum DatasetKind {
        REAL_PR,
        OFFLINE_SYNTHETIC
    }

    public LlmEvaluationDatasetMetadata {
        datasetId = requireText(datasetId, "datasetId");
        datasetVersion = requireText(datasetVersion, "datasetVersion");
        kind = Objects.requireNonNull(kind, "kind");
        requireNonNegative(sourceRepositoryCount, "sourceRepositoryCount");
        requireNonNegative(sampleCount, "sampleCount");
        requireNonNegative(fixedRegressionSamples, "fixedRegressionSamples");
        requireNonNegative(rollingObservationSamples, "rollingObservationSamples");
        if (fixedRegressionSamples + rollingObservationSamples != sampleCount) {
            throw new IllegalArgumentException(
                "fixedRegressionSamples + rollingObservationSamples must equal sampleCount"
            );
        }
        sampleFingerprint = sampleFingerprint == null
            ? ""
            : sampleFingerprint.trim().toLowerCase(Locale.ROOT);
        if (!sampleFingerprint.isEmpty() && !sampleFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sampleFingerprint must be a SHA-256 hexadecimal value");
        }
    }

    public static LlmEvaluationDatasetMetadata synthetic(
        String datasetId,
        String datasetVersion,
        int sampleCount
    ) {
        return new LlmEvaluationDatasetMetadata(
            datasetId,
            datasetVersion,
            DatasetKind.OFFLINE_SYNTHETIC,
            0,
            sampleCount,
            sampleCount,
            0,
            false,
            false,
            false,
            ""
        );
    }

    /**
     * Lists blockers for promoting an evaluation as the personal project's real PR baseline.
     * Synthetic/offline fixtures are intentionally rejected even when their code-level metrics
     * pass, preventing a false claim of real-world quality.
     */
    public List<String> validationBlockers(int observedSamples) {
        List<String> blockers = new ArrayList<>();
        if (kind != DatasetKind.REAL_PR) {
            blockers.add("DATASET_NOT_REAL_PR");
        }
        if (sourceRepositoryCount < 2) {
            blockers.add("DATASET_REPOSITORIES_BELOW_2");
        }
        if (sourceRepositoryCount > 3) {
            blockers.add("DATASET_REPOSITORIES_ABOVE_3");
        }
        if (sampleCount < 50) {
            blockers.add("DATASET_SAMPLES_BELOW_50");
        }
        if (sampleCount > 100) {
            blockers.add("DATASET_SAMPLES_ABOVE_100");
        }
        if (observedSamples != sampleCount) {
            blockers.add("DATASET_SAMPLE_COUNT_MISMATCH:" + observedSamples + "/" + sampleCount);
        }
        if (fixedRegressionSamples == 0) {
            blockers.add("DATASET_FIXED_REGRESSION_SPLIT_MISSING");
        }
        if (rollingObservationSamples == 0) {
            blockers.add("DATASET_ROLLING_OBSERVATION_SPLIT_MISSING");
        }
        if (!authorized) {
            blockers.add("DATASET_AUTHORIZATION_MISSING");
        }
        if (!anonymized) {
            blockers.add("DATASET_NOT_ANONYMIZED");
        }
        if (!humanReviewed) {
            blockers.add("DATASET_NOT_HUMAN_REVIEWED");
        }
        if (sampleFingerprint.isBlank()) {
            blockers.add("DATASET_FINGERPRINT_MISSING");
        }
        return List.copyOf(blockers);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("LLM evaluation " + field + " must not be blank");
        }
        return value.trim();
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException("LLM evaluation " + field + " must not be negative");
        }
    }
}
