package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

class ExternalRetryTimingTest {
    @Test
    void exponentialJitterIsBoundedAndServerRetryAfterIsNeverShortened() {
        var properties = new ExternalCallResilienceProperties.Instance();
        properties.setRetryMaxWaitMillis(5000);
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC);
        assertThat(ExternalRetryTiming.delay(1, null, properties, clock, 0)).isEqualTo(100);
        assertThat(ExternalRetryTiming.delay(3, null, properties, clock, 1)).isEqualTo(1200);
        assertThat(ExternalRetryTiming.delay(30, null, properties, clock, 1)).isEqualTo(5000);
        assertThat(ExternalRetryTiming.delay(1, failure("3"), properties, clock, 0)).isEqualTo(3000);
        assertThat(ExternalRetryTiming.delay(1, failure("Mon, 12 Oct 2026 12:00:04 GMT"), properties, clock, 0)).isEqualTo(4000);
        assertThatThrownBy(() -> ExternalRetryTiming.delay(1, failure("8"), properties, clock, 0))
            .hasMessageContaining("retry_after_exceeds_limit");
    }

    @Test
    void delayCannotOutliveBudgetAndScopeIsRemovedAfterFailure() {
        var properties = new ExternalCallResilienceProperties.Instance();
        var remaining = new AtomicLong(50_000_000);
        assertThatThrownBy(() -> ExternalRetryTiming.withinBudget(remaining::get,
            () -> ExternalRetryTiming.delay(1, null, properties))).hasMessageContaining("deadline_exhausted");
        // The prior tenant/task's deadline must not remain on a reused executor thread.
        assertThat(ExternalRetryTiming.delay(1, null, properties)).isBetween(100L, 300L);
    }

    private static RuntimeException failure(String hint) {
        HttpHeaders headers = new HttpHeaders(); headers.set(HttpHeaders.RETRY_AFTER, hint);
        return new RestClientResponseException("fixture", 429, "limited", headers, new byte[0], null);
    }
}
