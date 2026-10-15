package com.repoguard.agent.review.quality;

import com.repoguard.agent.review.quality.LlmModelReleaseRuntimeSupport.ReleaseQualityPolicy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReleaseQualityPolicyTest {
    @Test
    void exactThresholdsPass() {
        assertThat(ReleaseQualityPolicy.evaluate(evidence(true, ".90", ".80", ".95", ".05", ".05", 15000L))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "false,.90,.80,.95,.05,.05,15000,QUALITY_GATE_FAILED",
        "true,.8999,.80,.95,.05,.05,15000,PRECISION_BELOW_90",
        "true,.90,.7999,.95,.05,.05,15000,RECALL_BELOW_80",
        "true,.90,.80,.9499,.05,.05,15000,ANCHOR_RATE_BELOW_95",
        "true,.90,.80,.95,.0501,.05,15000,DUPLICATE_RATE_ABOVE_5",
        "true,.90,.80,.95,.05,.0501,15000,PARSE_FAILURE_RATE_ABOVE_5",
        "true,.90,.80,.95,.05,.05,15001,P95_LATENCY_ABOVE_15000_MS"
    })
    void reportsEachViolatedSpecification(boolean passed, String precision, String recall, String anchor,
                                          String duplicate, String parseFailure, long latency, String code) {
        assertThat(ReleaseQualityPolicy.blockerCodes(evidence(passed, precision, recall, anchor, duplicate, parseFailure, latency)))
            .containsExactly(code);
    }

    @Test
    void missingEvidenceCannotPass() {
        assertThat(ReleaseQualityPolicy.evaluate(new ReleaseQualityPolicy.Evidence(null, null, null, null, null, null, null)))
            .containsExactlyElementsOf(List.of(ReleaseQualityPolicy.Blocker.values()));
    }

    private ReleaseQualityPolicy.Evidence evidence(boolean passed, String precision, String recall, String anchor,
                                                   String duplicate, String parseFailure, long latency) {
        return new ReleaseQualityPolicy.Evidence(passed, new BigDecimal(precision), new BigDecimal(recall),
            new BigDecimal(anchor), new BigDecimal(duplicate), new BigDecimal(parseFailure), latency);
    }
}
