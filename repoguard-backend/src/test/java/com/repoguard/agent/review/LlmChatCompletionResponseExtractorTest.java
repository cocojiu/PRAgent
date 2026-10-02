package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LlmChatCompletionResponseExtractorTest {

    private final LlmChatCompletionResponseExtractor extractor = new LlmChatCompletionResponseExtractor(
        new ObjectMapper()
    );

    @Test
    void extractReadsChatCompletionContentAndUsage() throws Exception {
        LlmChatCompletionResponse response = extractor.extract("""
            {
              "choices": [{"message": {"content": "{\\"riskLevel\\":\\"LOW\\"}"}}],
              "usage": {
                "prompt_tokens": 12,
                "completion_tokens": 5,
                "total_tokens": 17
              }
            }
            """);

        assertThat(response.content()).contains("\"riskLevel\":\"LOW\"");
        assertThat(response.promptTokens()).isEqualTo(12);
        assertThat(response.completionTokens()).isEqualTo(5);
        assertThat(response.totalTokens()).isEqualTo(17);
    }

    @Test
    void extractFlattensTextContentParts() throws Exception {
        LlmChatCompletionResponse response = extractor.extract("""
            {"choices":[{"message":{"content":[
              {"type":"text","text":"first"},
              {"type":"text","text":"second"}
            ]}}]}
            """);

        assertThat(response.content()).isEqualTo("first\nsecond");
        assertThat(response.promptTokens()).isNull();
    }

    @Test
    void extractSupportsLegacyTextAndOutputFields() throws Exception {
        assertThat(extractor.extract("{\"choices\":[{\"text\":\"legacy\"}]}").content()).isEqualTo("legacy");
        assertThat(extractor.extract("{\"output_text\":\"output\"}").content()).isEqualTo("output");
        assertThat(extractor.extract("{\"content\":\"direct\"}").content()).isEqualTo("direct");
    }

    @Test
    void extractRejectsEmptyResponse() {
        assertThatThrownBy(() -> extractor.extract((String) null))
            .isInstanceOf(Exception.class);
    }

    @Test
    void normalizesInclusiveCachedInputWithoutTreatingMissingAsZero() throws Exception {
        for (int cached : new int[] {0, 40, 100}) {
            var response = extractor.extract("{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10,"
                + "\"total_tokens\":110,\"prompt_tokens_details\":{\"cached_tokens\":" + cached + "}}}");
            assertThat(response.promptTokens()).isEqualTo(100);
            assertThat(response.cachedInputTokens()).isEqualTo(cached);
            assertThat(response.usageSource()).isEqualTo("OPENAI_COMPATIBLE_CACHE_DETAILS");
        }
        var missing = extractor.extract("{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10}}");
        assertThat(missing.cachedInputTokens()).isNull();
        assertThat(missing.usageSource()).isEqualTo("CACHE_USAGE_UNKNOWN");
        for (String cached : new String[] {"101", "-1", "1.5", "\"40\"", "2147483648"}) {
            var response = extractor.extract("{\"usage\":{\"prompt_tokens\":100,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":" + cached + "}}}");
            assertThat(response.cachedInputTokens()).isNull();
            assertThat(response.usageSource()).isEqualTo("INVALID_CACHE_DETAILS");
        }
        assertThat(extractor.extract("{}").usageSource()).isEqualTo("UNKNOWN");
    }
}
