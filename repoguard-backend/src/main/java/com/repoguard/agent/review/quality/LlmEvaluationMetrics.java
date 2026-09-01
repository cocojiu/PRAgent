package com.repoguard.agent.review.quality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Operational metrics that complement the evaluator's precision/recall quality gate. */
public record LlmEvaluationMetrics(
    int labeledComments,
    int usefulComments,
    int falsePositiveComments,
    int publishAttempts,
    int publishedComments,
    int fixedComments,
    int ignoredComments,
    BigDecimal usefulCommentRate,
    BigDecimal falsePositiveCommentRate,
    BigDecimal publishSuccessRate,
    BigDecimal fixRate,
    BigDecimal ignoredRate,
    long p50LatencyMs,
    long p95LatencyMs,
    BigDecimal averageLatencyMs,
    BigDecimal averageTokensPerSample,
    BigDecimal averageCostPerSample,
    long ruleFindings,
    long llmFindings,
    long verifiedFindings,
    BigDecimal ruleContributionRate,
    BigDecimal llmContributionRate,
    BigDecimal verifiedContributionRate
) {

    private static final int SCALE = 4;

    public LlmEvaluationMetrics {
        labeledComments = Math.max(0, labeledComments);
        usefulComments = Math.max(0, usefulComments);
        falsePositiveComments = Math.max(0, falsePositiveComments);
        publishAttempts = Math.max(0, publishAttempts);
        publishedComments = Math.max(0, publishedComments);
        fixedComments = Math.max(0, fixedComments);
        ignoredComments = Math.max(0, ignoredComments);
        usefulCommentRate = normalizeRate(usefulCommentRate);
        falsePositiveCommentRate = normalizeRate(falsePositiveCommentRate);
        publishSuccessRate = normalizeRate(publishSuccessRate);
        fixRate = normalizeRate(fixRate);
        ignoredRate = normalizeRate(ignoredRate);
        p50LatencyMs = Math.max(0, p50LatencyMs);
        p95LatencyMs = Math.max(0, p95LatencyMs);
        averageLatencyMs = normalizeDecimal(averageLatencyMs);
        averageTokensPerSample = normalizeDecimal(averageTokensPerSample);
        averageCostPerSample = normalizeDecimal(averageCostPerSample);
        ruleFindings = Math.max(0, ruleFindings);
        llmFindings = Math.max(0, llmFindings);
        verifiedFindings = Math.max(0, verifiedFindings);
        ruleContributionRate = normalizeRate(ruleContributionRate);
        llmContributionRate = normalizeRate(llmContributionRate);
        verifiedContributionRate = normalizeRate(verifiedContributionRate);
    }

    public static LlmEvaluationMetrics from(List<LlmEvaluationObservation> observations) {
        List<LlmEvaluationObservation> samples = observations == null
            ? List.of()
            : observations.stream().filter(Objects::nonNull).toList();
        int labeled = (int) samples.stream().filter(sample -> sample.usefulComment() != null).count();
        int useful = (int) samples.stream().filter(sample -> Boolean.TRUE.equals(sample.usefulComment())).count();
        int falsePositive = (int) samples.stream()
            .filter(sample -> Boolean.FALSE.equals(sample.usefulComment()))
            .count();
        int publishAttempts = (int) samples.stream()
            .filter(LlmEvaluationObservation::commentPublishAttempted)
            .count();
        int published = (int) samples.stream()
            .filter(sample -> Boolean.TRUE.equals(sample.commentPublished()))
            .count();
        int fixed = (int) samples.stream()
            .filter(sample -> Boolean.TRUE.equals(sample.commentFixed()))
            .count();
        int ignored = (int) samples.stream()
            .filter(sample -> Boolean.TRUE.equals(sample.commentIgnored()))
            .count();
        long rule = samples.stream().mapToLong(LlmEvaluationObservation::ruleFindingCount).sum();
        long llm = samples.stream().mapToLong(LlmEvaluationObservation::llmFindingCount).sum();
        long verified = samples.stream().mapToLong(LlmEvaluationObservation::verifiedFindingCount).sum();
        long contributions = rule + llm + verified;
        long latencyTotal = samples.stream().mapToLong(LlmEvaluationObservation::latencyMs).sum();
        long tokenTotal = samples.stream().mapToLong(LlmEvaluationObservation::totalTokens).sum();
        BigDecimal costTotal = samples.stream()
            .map(LlmEvaluationObservation::estimatedCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Long> latencies = samples.stream()
            .map(LlmEvaluationObservation::latencyMs)
            .sorted(Comparator.naturalOrder())
            .toList();
        return new LlmEvaluationMetrics(
            labeled,
            useful,
            falsePositive,
            publishAttempts,
            published,
            fixed,
            ignored,
            ratio(useful, labeled),
            ratio(falsePositive, labeled),
            ratio(published, publishAttempts),
            ratio(fixed, (int) samples.stream().filter(sample -> sample.commentFixed() != null).count()),
            ratio(ignored, (int) samples.stream().filter(sample -> sample.commentIgnored() != null).count()),
            percentile(latencies, 0.50),
            percentile(latencies, 0.95),
            average(latencyTotal, samples.size()),
            average(tokenTotal, samples.size()),
            average(costTotal, samples.size()),
            rule,
            llm,
            verified,
            ratio(rule, contributions),
            ratio(llm, contributions),
            ratio(verified, contributions)
        );
    }

    public static LlmEvaluationMetrics empty() {
        return from(List.of());
    }

    private static long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0L;
        }
        int index = Math.max(0, (int) Math.ceil(sortedValues.size() * percentile) - 1);
        return sortedValues.get(Math.min(index, sortedValues.size() - 1));
    }

    private static BigDecimal average(long total, int count) {
        if (count <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(total)
            .divide(BigDecimal.valueOf(count), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(BigDecimal total, int count) {
        if (count <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return total.divide(BigDecimal.valueOf(count), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
            .divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal normalizeRate(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP) : value;
    }

    private static BigDecimal normalizeDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP) : value;
    }
}
