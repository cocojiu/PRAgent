package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class ExternalRetryAfterHintTest {

    @Test
    void retryAfterCleansUnsafeCharactersFromDetailValue() {
        assertThat(ExternalRetryAfterHint.retryAfter(
            "request failed retryAfter=60<script> responseBody={}"
        )).isEqualTo("60script");
    }

    @Test
    void suggestionSuffixUsesCleanedRetryAfterValue() {
        assertThat(ExternalRetryAfterHint.suggestionSuffix(
            "request failed retryAfter=Wed, 21 Oct 2026 07:28:00 GMT responseBody={}"
        )).isEqualTo("建议等待 Wed, 21 Oct 2026 07:28:00 GMT 后再重试。");
    }

    @Test
    void fromHeadersReturnsBlankForMissingHeader() {
        assertThat(ExternalRetryAfterHint.fromHeaders(null)).isEmpty();
        assertThat(ExternalRetryAfterHint.fromHeaders(HttpHeaders.EMPTY)).isEmpty();
    }
}
