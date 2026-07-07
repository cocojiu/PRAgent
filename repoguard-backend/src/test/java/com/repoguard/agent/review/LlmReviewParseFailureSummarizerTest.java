package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmReviewParseFailureSummarizerTest {

    private final LlmReviewParseFailureSummarizer summarizer = new LlmReviewParseFailureSummarizer();

    @Test
    void summarizesLengthAndReasonWithoutIncludingRawContent() {
        String summary = summarizer.summarize(
            "模型回答：没有 JSON，包含敏感上下文 password=secret",
            new IllegalArgumentException("LLM result does not contain a JSON object")
        );

        assertThat(summary).contains("length=");
        assertThat(summary).contains("reason=LLM result does not contain a JSON object");
        assertThat(summary).doesNotContain("模型回答");
        assertThat(summary).doesNotContain("password=secret");
    }

    @Test
    void fallsBackToExceptionTypeWhenMessageIsBlank() {
        String summary = summarizer.summarize("{}", new IllegalStateException("   "));

        assertThat(summary).isEqualTo("length=2, reason=IllegalStateException");
    }

    @Test
    void handlesNullContentAndNullException() {
        String summary = summarizer.summarize(null, null);

        assertThat(summary).isEqualTo("length=0, reason=unknown");
    }

    @Test
    void sanitizesSensitiveValuesFromExceptionMessage() {
        String summary = summarizer.summarize(
            "{}",
            new IllegalArgumentException("parse failed token=raw-token Authorization: Bearer raw.bearer-token")
        );

        assertThat(summary).contains("token=****");
        assertThat(summary).contains("Bearer ****");
        assertThat(summary).doesNotContain("raw-token", "raw.bearer-token");
    }

    @Test
    void truncatesLongExceptionMessage() {
        String summary = summarizer.summarize("{}", new IllegalArgumentException("x".repeat(400)));

        assertThat(summary).startsWith("length=2, reason=");
        assertThat(summary).endsWith("...");
        assertThat(summary.length()).isLessThan(270);
    }
}
