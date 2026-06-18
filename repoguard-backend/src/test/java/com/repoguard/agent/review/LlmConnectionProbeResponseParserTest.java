package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LlmConnectionProbeResponseParserTest {

    private final LlmConnectionProbeResponseParser parser = new LlmConnectionProbeResponseParser(new ObjectMapper());

    @Test
    void extractReviewContentReadsChatCompletionMessageContent() throws Exception {
        String response = """
            {"choices":[{"message":{"content":"{\\"riskLevel\\":\\"INFO\\",\\"findings\\":[]}"}}]}
            """;

        String content = parser.extractReviewContent(response);

        assertThat(content).contains("\"riskLevel\":\"INFO\"");
        parser.validateReviewJson(content);
    }

    @Test
    void extractReviewContentFlattensContentPartArray() throws Exception {
        String response = """
            {"choices":[{"message":{"content":[{"type":"text","text":"```json\\n{\\"riskLevel\\":\\"INFO\\",\\"findings\\":[]}\\n```"}]}}]}
            """;

        String content = parser.extractReviewContent(response);

        assertThat(content).contains("riskLevel");
        parser.validateReviewJson(content);
    }

    @Test
    void validateReviewJsonRejectsMalformedReviewPayload() {
        assertThatThrownBy(() -> parser.validateReviewJson("OK"))
            .isInstanceOf(RuntimeException.class);
    }
}
