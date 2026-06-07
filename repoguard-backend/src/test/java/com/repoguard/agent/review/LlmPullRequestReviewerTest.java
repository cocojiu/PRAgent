package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LlmPullRequestReviewerTest {

    private final LlmPullRequestReviewer reviewer = new LlmPullRequestReviewer(
        null,
        null,
        null,
        new ObjectMapper(),
        null
    );

    @Test
    void parsesStrictJsonResult() {
        ReviewResult result = reviewer.parseLlmResult("""
            {
              "riskLevel": "MEDIUM",
              "findings": [
                {
                  "severity": "LOW",
                  "filePath": "src/App.vue",
                  "lineNumber": 42,
                  "message": "按钮没有禁用态",
                  "recommendation": "补充禁用态"
                }
              ]
            }
            """);

        assertThat(result.llmStatus()).isEqualTo("COMPLETED");
        assertThat(result.riskLevel()).isEqualTo("MEDIUM");
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().filePath()).isEqualTo("src/App.vue");
        assertThat(result.findings().getFirst().lineNumber()).isEqualTo(42);
    }

    @Test
    void parsesJsonWrappedInMarkdownFenceAndText() {
        ReviewResult result = reviewer.parseLlmResult("""
            下面是审查结果：
            ```json
            {
              "risk_level": "low",
              "findings": []
            }
            ```
            如上。
            """);

        assertThat(result.riskLevel()).isEqualTo("LOW");
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void acceptsCommonFindingFieldAliases() {
        ReviewResult result = reviewer.parseLlmResult("""
            {
              "risk": "high",
              "findings": [
                {
                  "level": "medium",
                  "file": "src/detail.ts",
                  "line": "108",
                  "description": "轮询没有退避",
                  "suggestion": "增加指数退避"
                }
              ]
            }
            """);

        ReviewFindingResult finding = result.findings().getFirst();
        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(finding.severity()).isEqualTo("MEDIUM");
        assertThat(finding.filePath()).isEqualTo("src/detail.ts");
        assertThat(finding.lineNumber()).isEqualTo(108);
        assertThat(finding.message()).isEqualTo("轮询没有退避");
        assertThat(finding.recommendation()).isEqualTo("增加指数退避");
    }

    @Test
    void treatsNonArrayFindingsAsEmpty() {
        ReviewResult result = reviewer.parseLlmResult("""
            {
              "riskLevel": "INFO",
              "findings": null
            }
            """);

        assertThat(result.findings()).isEmpty();
    }

    @Test
    void includesResponseSummaryWhenParsingFails() {
        assertThatThrownBy(() -> reviewer.parseLlmResult("模型回答：没有 JSON。"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unable to parse LLM review result")
            .hasMessageContaining("模型回答");
    }
}
