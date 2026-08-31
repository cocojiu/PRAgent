package com.repoguard.agent.review.quality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic evaluator for manually labelled provider samples. It deliberately refuses to
 * declare a version promotable before the minimum sample count and all quality thresholds pass.
 */
public final class LlmQualityEvaluator {

    public static final int DEFAULT_MINIMUM_SAMPLES = 30;
    private static final BigDecimal MIN_PRECISION = new BigDecimal("0.90");
    private static final BigDecimal MIN_RECALL = new BigDecimal("0.80");
    private static final BigDecimal MIN_ANCHOR_RATE = new BigDecimal("0.95");
    private static final BigDecimal MAX_DUPLICATE_RATE = new BigDecimal("0.05");
    private static final BigDecimal MAX_PARSE_FAILURE_RATE = new BigDecimal("0.05");

    private LlmQualityEvaluator() {
    }

    public static LlmEvaluationReport evaluate(
        LlmEvaluationVersion version,
        List<LlmEvaluationObservation> observations
    ) {
        return evaluateInternal(version, null, observations, DEFAULT_MINIMUM_SAMPLES);
    }

    public static LlmEvaluationReport evaluate(
        LlmEvaluationVersion version,
        List<LlmEvaluationObservation> observations,
        int minimumSamples
    ) {
        return evaluateInternal(version, null, observations, minimumSamples);
    }

    public static LlmEvaluationReport evaluate(
        LlmEvaluationVersion version,
        LlmEvaluationDatasetMetadata dataset,
        List<LlmEvaluationObservation> observations
    ) {
        return evaluate(version, dataset, observations, DEFAULT_MINIMUM_SAMPLES);
    }

    public static LlmEvaluationReport evaluate(
        LlmEvaluationVersion version,
        LlmEvaluationDatasetMetadata dataset,
        List<LlmEvaluationObservation> observations,
        int minimumSamples
    ) {
        return evaluateInternal(version, dataset, observations, minimumSamples);
    }

    private static LlmEvaluationReport evaluateInternal(
        LlmEvaluationVersion version,
        LlmEvaluationDatasetMetadata dataset,
        List<LlmEvaluationObservation> observations,
        int minimumSamples
    ) {
        Objects.requireNonNull(version, "version");
        if (minimumSamples < 1) {
            throw new IllegalArgumentException("minimumSamples must be positive");
        }
        List<LlmEvaluationObservation> samples = observations == null
            ? List.of()
            : observations.stream().filter(Objects::nonNull).toList();
        Set<String> ids = new LinkedHashSet<>();
        samples.forEach(sample -> {
            if (!ids.add(sample.caseId())) {
                throw new IllegalArgumentException("Duplicate LLM evaluation case id: " + sample.caseId());
            }
        });

        int expected = (int) samples.stream().filter(LlmEvaluationObservation::expectedFinding).count();
        int predicted = (int) samples.stream().filter(LlmEvaluationObservation::predictedFinding).count();
        int truePositives = (int) samples.stream()
            .filter(sample -> sample.expectedFinding() && sample.predictedFinding())
            .count();
        int falsePositives = (int) samples.stream()
            .filter(sample -> !sample.expectedFinding() && sample.predictedFinding())
            .count();
        int falseNegatives = (int) samples.stream()
            .filter(sample -> sample.expectedFinding() && !sample.predictedFinding())
            .count();
        int anchored = (int) samples.stream()
            .filter(sample -> sample.predictedFinding() && sample.anchorValid())
            .count();
        int parseFailures = (int) samples.stream().filter(sample -> !sample.parseSucceeded()).count();
        int duplicatePredictions = duplicatePredictions(samples);
        String observedFingerprint = sampleFingerprint(samples);
        int observedFixedRegressionSamples = (int) samples.stream()
            .filter(sample -> sample.split() == LlmEvaluationObservation.EvaluationSplit.FIXED_REGRESSION)
            .count();
        int observedRollingObservationSamples = (int) samples.stream()
            .filter(sample -> sample.split() == LlmEvaluationObservation.EvaluationSplit.ROLLING_OBSERVATION)
            .count();
        int observationsWithoutSplit = (int) samples.stream()
            .filter(sample -> sample.split() == LlmEvaluationObservation.EvaluationSplit.UNSPECIFIED)
            .count();
        Set<String> sourceRepositoryKeys = samples.stream()
            .map(LlmEvaluationObservation::sourceRepositoryKey)
            .filter(repositoryKey -> !repositoryKey.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int observedSourceRepositories = sourceRepositoryKeys.size();
        int observationsWithoutRepository = (int) samples.stream()
            .filter(sample -> sample.sourceRepositoryKey().isBlank())
            .count();
        BigDecimal precision = ratio(truePositives, predicted);
        BigDecimal recall = ratio(truePositives, expected);
        BigDecimal anchorRate = ratio(anchored, predicted);
        BigDecimal duplicateRate = ratio(duplicatePredictions, predicted);
        BigDecimal parseFailureRate = ratio(parseFailures, samples.size());
        List<String> blockers = new ArrayList<>();
        if (samples.size() < minimumSamples) {
            blockers.add("INSUFFICIENT_SAMPLE:" + samples.size() + "/" + minimumSamples);
        }
        if (precision.compareTo(MIN_PRECISION) < 0) {
            blockers.add("PRECISION_BELOW_90");
        }
        if (recall.compareTo(MIN_RECALL) < 0) {
            blockers.add("RECALL_BELOW_80");
        }
        if (anchorRate.compareTo(MIN_ANCHOR_RATE) < 0) {
            blockers.add("ANCHOR_RATE_BELOW_95");
        }
        if (duplicateRate.compareTo(MAX_DUPLICATE_RATE) > 0) {
            blockers.add("DUPLICATE_RATE_ABOVE_5");
        }
        if (parseFailureRate.compareTo(MAX_PARSE_FAILURE_RATE) > 0) {
            blockers.add("PARSE_FAILURE_RATE_ABOVE_5");
        }
        List<String> datasetBlockers = dataset == null
            ? List.of()
            : dataset.validationBlockers(
                samples.size(),
                observedFingerprint,
                observedSourceRepositories,
                observationsWithoutRepository,
                observedFixedRegressionSamples,
                observedRollingObservationSamples,
                observationsWithoutSplit
            );
        if (dataset != null) {
            blockers.addAll(datasetBlockers);
            if (!version.reproducible()) {
                blockers.add("VERSION_METADATA_INCOMPLETE");
            }
        }
        return new LlmEvaluationReport(
            version,
            observedFingerprint,
            samples.size(),
            expected,
            predicted,
            truePositives,
            falsePositives,
            falseNegatives,
            precision,
            recall,
            wilsonLowerBound(truePositives, predicted),
            anchorRate,
            duplicateRate,
            parseFailureRate,
            severityConfusion(samples),
            samples.stream().mapToLong(LlmEvaluationObservation::latencyMs).sum(),
            samples.stream().mapToLong(LlmEvaluationObservation::totalTokens).sum(),
            samples.stream()
                .map(LlmEvaluationObservation::estimatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add),
            blockers,
            samples.size() >= minimumSamples && (dataset == null || (
                datasetBlockers.isEmpty() && version.reproducible()
            )),
            dataset,
            LlmEvaluationMetrics.from(samples)
        );
    }

    private static int duplicatePredictions(List<LlmEvaluationObservation> samples) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LlmEvaluationObservation sample : samples) {
            if (sample.predictedFinding() && !sample.predictionKey().isBlank()) {
                counts.merge(sample.predictionKey(), 1, Integer::sum);
            }
        }
        return counts.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum();
    }

    private static Map<String, Map<String, Long>> severityConfusion(
        List<LlmEvaluationObservation> samples
    ) {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        samples.stream()
            .filter(sample -> sample.expectedFinding() || sample.predictedFinding())
            .sorted(Comparator.comparing(LlmEvaluationObservation::expectedSeverity)
                .thenComparing(LlmEvaluationObservation::predictedSeverity))
            .forEach(sample -> result
                .computeIfAbsent(sample.expectedSeverity(), ignored -> new LinkedHashMap<>())
                .merge(sample.predictedSeverity(), 1L, Long::sum));
        Map<String, Map<String, Long>> immutable = new LinkedHashMap<>();
        result.forEach((expected, actual) -> immutable.put(expected, Map.copyOf(actual)));
        return Map.copyOf(immutable);
    }

    private static String sampleFingerprint(List<LlmEvaluationObservation> samples) {
        String input = samples.stream()
            .map(LlmEvaluationObservation::caseId)
            .sorted()
            .reduce((first, second) -> first + "\n" + second)
            .orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for evaluation fingerprints", ex);
        }
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        return BigDecimal.valueOf(numerator)
            .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal wilsonLowerBound(int successes, int trials) {
        if (trials <= 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        double n = trials;
        double z = 1.959963984540054;
        double p = successes / n;
        double denominator = 1 + z * z / n;
        double centre = p + z * z / (2 * n);
        double margin = z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n);
        double lower = Math.max(0, (centre - margin) / denominator);
        return BigDecimal.valueOf(lower).setScale(4, RoundingMode.HALF_UP);
    }
}
