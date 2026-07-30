package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class LlmReviewResultParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmReviewResultParser parser = new LlmReviewResultParser(
        objectMapper,
        new LlmReviewJsonExtractor(),
        new LlmReviewSchemaRepairer(objectMapper),
        new LlmReviewFindingMapper(),
        new LlmReviewParseFailureSummarizer()
    );

    @Test
    void constructorRejectsMissingExplicitDependencies() {
        assertMissing("objectMapper", () -> parser(
            null,
            new LlmReviewJsonExtractor(),
            new LlmReviewSchemaRepairer(objectMapper),
            new LlmReviewFindingMapper(),
            new LlmReviewParseFailureSummarizer()
        ));
        assertMissing("jsonExtractor", () -> parser(
            objectMapper,
            null,
            new LlmReviewSchemaRepairer(objectMapper),
            new LlmReviewFindingMapper(),
            new LlmReviewParseFailureSummarizer()
        ));
        assertMissing("schemaRepairer", () -> parser(
            objectMapper,
            new LlmReviewJsonExtractor(),
            null,
            new LlmReviewFindingMapper(),
            new LlmReviewParseFailureSummarizer()
        ));
        assertMissing("findingMapper", () -> parser(
            objectMapper,
            new LlmReviewJsonExtractor(),
            new LlmReviewSchemaRepairer(objectMapper),
            null,
            new LlmReviewParseFailureSummarizer()
        ));
        assertMissing("failureSummarizer", () -> parser(
            objectMapper,
            new LlmReviewJsonExtractor(),
            new LlmReviewSchemaRepairer(objectMapper),
            new LlmReviewFindingMapper(),
            null
        ));
    }

    @Test
    void parsesStrictJsonResult() {
        ReviewResult result = parser.parse("""
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
        assertThat(result.findings().getFirst().confidence()).isEqualTo("MEDIUM");
        assertThat(result.findings().getFirst().isBlocking()).isFalse();
        assertThat(result.findings().getFirst().reviewDimension()).isEqualTo("LLM");
    }

    @Test
    void parsesExplainabilityFieldsWhenPresent() {
        ReviewResult result = parser.parse("""
            {
              "riskLevel": "HIGH",
              "findings": [
                {
                  "severity": "HIGH",
                  "filePath": "src/Gateway.java",
                  "lineNumber": 20,
                  "message": "Missing authorization",
                  "recommendation": "Add a role gate",
                  "confidence": "high",
                  "evidence": "POST /admin is public",
                  "impact": "Unauthorized users can change settings",
                  "fixExample": "@RequireRole(\\"ADMIN\\")",
                  "isBlocking": true,
                  "blockingCandidate": true,
                  "reviewDimension": "ACCESS_CONTROL"
                }
              ]
            }
            """);

        ReviewFindingResult finding = result.findings().getFirst();
        assertThat(finding.confidence()).isEqualTo("HIGH");
        assertThat(finding.evidence()).isEqualTo("POST /admin is public");
        assertThat(finding.impact()).isEqualTo("Unauthorized users can change settings");
        assertThat(finding.fixExample()).isEqualTo("@RequireRole(\"ADMIN\")");
        assertThat(finding.isBlocking()).isFalse();
        assertThat(finding.blockingCandidate()).isTrue();
        assertThat(finding.reviewDimension()).isEqualTo("ACCESS_CONTROL");
    }

    @Test
    void parsesJsonWrappedInMarkdownFenceAndText() {
        ReviewResult result = parser.parse("""
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
    void keepsBracesInsideJsonStringsWhenExtractingWrappedJson() {
        ReviewResult result = parser.parse("""
            Here is the review result:
            {
              "riskLevel": "LOW",
              "findings": [
                {
                  "severity": "LOW",
                  "filePath": "src/parser.ts",
                  "lineNumber": "17",
                  "message": "Do not stop at {placeholder} text.",
                  "recommendation": "Keep scanning until the real object closes."
                }
              ]
            }
            Thanks.
            """);

        ReviewFindingResult finding = result.findings().getFirst();
        assertThat(result.riskLevel()).isEqualTo("LOW");
        assertThat(finding.filePath()).isEqualTo("src/parser.ts");
        assertThat(finding.lineNumber()).isEqualTo(17);
        assertThat(finding.message()).isEqualTo("Do not stop at {placeholder} text.");
    }

    @Test
    void acceptsCommonFindingFieldAliases() {
        ReviewResult result = parser.parse("""
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
        ReviewResult result = parser.parse("""
            {
              "riskLevel": "INFO",
              "findings": null
            }
            """);

        assertThat(result.findings()).isEmpty();
    }

    @Test
    void includesResponseSummaryWhenParsingFails() {
        assertThatThrownBy(() -> parser.parse("模型回答：没有 JSON。"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unable to parse LLM review result")
            .hasMessageContaining("length=")
            .satisfies(error -> assertThat(error.getMessage()).doesNotContain("模型回答"));
    }

    private void assertMissing(String dependencyName, ThrowingCallable callable) {
        assertThatThrownBy(callable)
            .isInstanceOf(NullPointerException.class)
            .hasMessage(dependencyName);
    }

    private LlmReviewResultParser parser(
        ObjectMapper objectMapper,
        LlmReviewJsonExtractor jsonExtractor,
        LlmReviewSchemaRepairer schemaRepairer,
        LlmReviewFindingMapper findingMapper,
        LlmReviewParseFailureSummarizer failureSummarizer
    ) {
        return new LlmReviewResultParser(
            objectMapper,
            jsonExtractor,
            schemaRepairer,
            findingMapper,
            failureSummarizer
        );
    }
}
