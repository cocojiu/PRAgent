package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LlmReviewJsonExtractorTest {

    private final LlmReviewJsonExtractor extractor = new LlmReviewJsonExtractor();

    @Test
    void extractsJsonObjectFromMarkdownFenceAndSurroundingText() {
        String json = extractor.extractJsonObject("""
            下面是审查结果：
            ```json
            {"riskLevel":"LOW","findings":[]}
            ```
            如上。
            """);

        assertThat(json).isEqualTo("{\"riskLevel\":\"LOW\",\"findings\":[]}");
    }

    @Test
    void keepsNestedObjectsAndBracesInsideStrings() {
        String json = extractor.extractJsonObject("""
            text before
            {
              "riskLevel": "LOW",
              "metadata": {"source": "llm"},
              "message": "do not stop at {placeholder} or escaped \\" braces"
            }
            text after
            """);

        assertThat(json)
            .contains("\"metadata\": {\"source\": \"llm\"}")
            .contains("do not stop at {placeholder}");
        assertThat(json).endsWith("}");
    }

    @Test
    void rejectsMissingOrIncompleteJson() {
        assertThatThrownBy(() -> extractor.extractJsonObject("plain text"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not contain a JSON object");

        assertThatThrownBy(() -> extractor.extractJsonObject("{\"riskLevel\":\"LOW\""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("incomplete JSON object");
    }
}
