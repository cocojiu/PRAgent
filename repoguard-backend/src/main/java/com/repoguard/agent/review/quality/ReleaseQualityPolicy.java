package com.repoguard.agent.review.quality;

import com.repoguard.agent.dto.LlmModelReleaseDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Shared quality specification; admission and runtime retain their own additional constraints. */
final class ReleaseQualityPolicy {
    private static final BigDecimal MIN_PRECISION = new BigDecimal("0.90");
    private static final BigDecimal MIN_RECALL = new BigDecimal("0.80");
    private static final BigDecimal MIN_ANCHOR = new BigDecimal("0.95");
    private static final BigDecimal MAX_ERROR_RATE = new BigDecimal("0.05");
    private static final long MAX_P95_LATENCY_MS = 15_000L;

    private ReleaseQualityPolicy() { }

    static List<Blocker> evaluate(Evidence evidence) {
        List<Blocker> blockers = new ArrayList<>();
        if (!Boolean.TRUE.equals(evidence.qualityGatePassed())) blockers.add(Blocker.QUALITY_GATE_FAILED);
        if (below(evidence.precision(), MIN_PRECISION)) blockers.add(Blocker.PRECISION_BELOW_90);
        if (below(evidence.recall(), MIN_RECALL)) blockers.add(Blocker.RECALL_BELOW_80);
        if (below(evidence.anchor(), MIN_ANCHOR)) blockers.add(Blocker.ANCHOR_RATE_BELOW_95);
        if (above(evidence.duplicate(), MAX_ERROR_RATE)) blockers.add(Blocker.DUPLICATE_RATE_ABOVE_5);
        if (above(evidence.parseFailure(), MAX_ERROR_RATE)) blockers.add(Blocker.PARSE_FAILURE_RATE_ABOVE_5);
        if (evidence.p95LatencyMs() == null || evidence.p95LatencyMs() > MAX_P95_LATENCY_MS) {
            blockers.add(Blocker.P95_LATENCY_ABOVE_15000_MS);
        }
        return List.copyOf(blockers);
    }

    static List<String> blockerCodes(Evidence evidence) {
        return evaluate(evidence).stream().map(Enum::name).toList();
    }

    private static boolean below(BigDecimal actual, BigDecimal minimum) {
        return actual == null || actual.compareTo(minimum) < 0;
    }

    private static boolean above(BigDecimal actual, BigDecimal maximum) {
        return actual == null || actual.compareTo(maximum) > 0;
    }

    record Evidence(Boolean qualityGatePassed, BigDecimal precision, BigDecimal recall, BigDecimal anchor,
                    BigDecimal duplicate, BigDecimal parseFailure, Long p95LatencyMs) {
        static Evidence from(LlmModelReleaseDto release) {
            return new Evidence(release.qualityGatePassed(), release.precisionRate(), release.recallRate(),
                release.anchorRate(), release.duplicateRate(), release.parseFailureRate(), release.p95LatencyMs());
        }
    }

    enum Blocker {
        QUALITY_GATE_FAILED, PRECISION_BELOW_90, RECALL_BELOW_80, ANCHOR_RATE_BELOW_95,
        DUPLICATE_RATE_ABOVE_5, PARSE_FAILURE_RATE_ABOVE_5, P95_LATENCY_ABOVE_15000_MS
    }
}
