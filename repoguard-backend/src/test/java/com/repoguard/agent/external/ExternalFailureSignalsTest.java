package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class ExternalFailureSignalsTest {

    @Test
    void extractsStatusCodeFromNormalizedDetail() {
        assertThat(ExternalFailureSignals.statusCodeFromDetail("request failed status=429 retryAfter=60"))
            .isEqualTo(429);
        assertThat(ExternalFailureSignals.statusCodeFromDetail("request failed status=abc"))
            .isNull();
        assertThat(ExternalFailureSignals.statusCodeFromDetail("request failed"))
            .isNull();
    }

    @Test
    void detectsRetryAfterSignal() {
        assertThat(ExternalFailureSignals.hasRetryAfterSignal("request failed retryafter=60")).isTrue();
        assertThat(ExternalFailureSignals.hasRetryAfterSignal("request failed retry-after: 60")).isTrue();
        assertThat(ExternalFailureSignals.hasRetryAfterSignal("request failed")).isFalse();
    }

    @Test
    void extractsRetryAfterValueBeforeResponseBody() {
        assertThat(ExternalFailureSignals.retryAfterFromDetail(
            "request failed status=429 retryAfter=60 responseBody={}"
        )).isEqualTo("60");
        assertThat(ExternalFailureSignals.retryAfterFromDetail(
            "request failed retryAfter=60 rateLimitRemaining=0 rateLimitReset=1763456789 responseBody={}"
        )).isEqualTo("60");
        assertThat(ExternalFailureSignals.retryAfterFromDetail(
            "request failed retryAfter=60 rateLimitLimit=5000 rateLimitUsed=4999 rateLimitResource=core"
        )).isEqualTo("60");
        assertThat(ExternalFailureSignals.retryAfterFromDetail("request failed retryAfter=Wed, 21 Oct 2026 07:28:00 GMT"))
            .isEqualTo("Wed, 21 Oct 2026 07:28:00 GMT");
        assertThat(ExternalFailureSignals.retryAfterFromDetail("request failed")).isEmpty();
    }

    @Test
    void normalizesExceptionMessageForDetailScanning() {
        assertThat(ExternalFailureSignals.normalizedDetail(new IllegalStateException("HTTP Status=500\nFailed")))
            .isEqualTo("http status=500\nfailed");
        assertThat(ExternalFailureSignals.normalizedDetail(null)).isEmpty();
    }

    @Test
    void detectsSocketTimeoutCause() {
        RuntimeException failure = new RuntimeException("request failed", new SocketTimeoutException("read timed out"));

        assertThat(ExternalFailureSignals.hasTimeoutSignal(failure, "", false)).isTrue();
    }

    @Test
    void genericTimeoutExceptionIsOptIn() {
        RuntimeException failure = new RuntimeException("request failed", new TimeoutException("operation expired"));

        assertThat(ExternalFailureSignals.hasTimeoutSignal(failure, "", false)).isFalse();
        assertThat(ExternalFailureSignals.hasTimeoutSignal(failure, "", true)).isTrue();
    }

    @Test
    void detectsTimeoutTextWithoutThrowable() {
        assertThat(ExternalFailureSignals.hasTimeoutSignal(null, "connect timed out", false)).isTrue();
    }
}
