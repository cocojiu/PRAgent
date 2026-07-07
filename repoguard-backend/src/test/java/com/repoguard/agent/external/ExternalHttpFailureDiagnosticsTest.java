package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExternalHttpFailureDiagnosticsTest {

    @Test
    void extractsRetryAfterAndRateLimitFieldsFromFailureDetail() {
        ExternalHttpFailureDiagnostics diagnostics = ExternalHttpFailureDiagnostics.fromDetail(
            "GitHub failed status=429 retryAfter=60 rateLimitLimit=5000 "
                + "rateLimitRemaining=0 rateLimitUsed=5000 rateLimitReset=1763456789 "
                + "rateLimitResource=core responseBody={}"
        );

        assertThat(diagnostics.retryAfter()).isEqualTo("60");
        assertThat(diagnostics.rateLimitLimit()).isEqualTo("5000");
        assertThat(diagnostics.rateLimitRemaining()).isEqualTo("0");
        assertThat(diagnostics.rateLimitUsed()).isEqualTo("5000");
        assertThat(diagnostics.rateLimitReset()).isEqualTo("1763456789");
        assertThat(diagnostics.rateLimitResource()).isEqualTo("core");
        assertThat(diagnostics.hasRateLimitSignal()).isTrue();
    }

    @Test
    void acceptsLowerCaseDetailMarkers() {
        ExternalHttpFailureDiagnostics diagnostics = ExternalHttpFailureDiagnostics.fromDetail(
            "github failed retryafter=Wed, 21 Oct 2026 07:28:00 GMT "
                + "ratelimitremaining=0 responsebody={}"
        );

        assertThat(diagnostics.retryAfter()).isEqualTo("Wed, 21 Oct 2026 07:28:00 GMT");
        assertThat(diagnostics.rateLimitRemaining()).isEqualTo("0");
    }

    @Test
    void cleansAndTruncatesDiagnosticValues() {
        ExternalHttpFailureDiagnostics diagnostics = ExternalHttpFailureDiagnostics.fromDetail(
            "failed retryAfter= 60<script> "
                + "rateLimitResource=" + "a".repeat(80) + " responseBody={}"
        );

        assertThat(diagnostics.retryAfter()).isEqualTo("60script");
        assertThat(diagnostics.rateLimitResource())
            .isEqualTo("a".repeat(64))
            .doesNotContain("<", ">");
    }

    @Test
    void emptyDetailProducesEmptyDiagnostics() {
        ExternalHttpFailureDiagnostics diagnostics = ExternalHttpFailureDiagnostics.fromDetail(null);

        assertThat(diagnostics.hasRetryAfter()).isFalse();
        assertThat(diagnostics.hasRateLimitDiagnostics()).isFalse();
        assertThat(diagnostics.hasRateLimitSignal()).isFalse();
    }
}
