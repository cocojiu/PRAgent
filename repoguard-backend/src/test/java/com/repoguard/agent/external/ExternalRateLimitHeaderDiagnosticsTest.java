package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class ExternalRateLimitHeaderDiagnosticsTest {

    @Test
    void appendsOnlyWhitelistedCleanRateLimitHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Limit", " 5,000 ");
        headers.add("X-RateLimit-Remaining", " 12<script> ");
        headers.add("X-RateLimit-Used", "4");
        headers.add("X-RateLimit-Reset", "Wed, 21 Oct 2026 07:28:00 GMT\nunsafe");
        headers.add("X-RateLimit-Resource", " core/search ");
        headers.add("X-Secret-Debug", "raw-token");
        StringBuilder message = new StringBuilder("failed");

        ExternalRateLimitHeaderDiagnostics.from(headers).appendTo(message);

        assertThat(message.toString()).contains(
            "rateLimitLimit=5,000",
            "rateLimitRemaining=12script",
            "rateLimitUsed=4",
            "rateLimitReset=Wed, 21 Oct 2026 07:28:00 GMTunsafe",
            "rateLimitResource=coresearch"
        );
        assertThat(message.toString()).doesNotContain("raw-token", "<", ">");
    }

    @Test
    void trimsLongHeaderValues() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Resource", "a".repeat(80));
        StringBuilder message = new StringBuilder("failed");

        ExternalRateLimitHeaderDiagnostics.from(headers).appendTo(message);

        assertThat(message.toString())
            .contains("rateLimitResource=" + "a".repeat(64))
            .doesNotContain("a".repeat(65));
    }

    @Test
    void skipsMissingHeaders() {
        StringBuilder message = new StringBuilder("failed");

        ExternalRateLimitHeaderDiagnostics.from(null).appendTo(message);

        assertThat(message.toString()).isEqualTo("failed");
    }
}
