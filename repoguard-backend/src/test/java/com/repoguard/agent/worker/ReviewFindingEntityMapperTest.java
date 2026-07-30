package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.review.FindingProvenance;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewExecutionProvenance;
import com.repoguard.agent.review.ReviewResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewFindingEntityMapperTest {

    private final ReviewFindingEntityMapper mapper = new ReviewFindingEntityMapper();

    @Test
    void mapsReviewFindingResultToFindingEntity() {
        ReviewFinding finding = mapper.toEntity(
            42L,
            new ReviewFindingResult(
                "HIGH",
                "RULE",
                "RG-JAVA-001",
                "src/App.java",
                10,
                "Use logger",
                "Replace stdout",
                "HIGH",
                "System.out.println",
                "Missing observability",
                "logger.info(...)",
                true,
                "PROJECT_RULE"
            )
        );

        assertThat(finding.getTaskId()).isEqualTo(42L);
        assertThat(finding.getCategory()).isEqualTo("FINDING");
        assertThat(finding.getSeverity()).isEqualTo("HIGH");
        assertThat(finding.getSource()).isEqualTo("RULE");
        assertThat(finding.getRuleId()).isEqualTo("RG-JAVA-001");
        assertThat(finding.getFilePath()).isEqualTo("src/App.java");
        assertThat(finding.getLineNumber()).isEqualTo(10);
        assertThat(finding.getMessage()).isEqualTo("Use logger");
        assertThat(finding.getRecommendation()).isEqualTo("Replace stdout");
        assertThat(finding.getConfidence()).isEqualTo("HIGH");
        assertThat(finding.getEvidence()).isEqualTo("System.out.println");
        assertThat(finding.getImpact()).isEqualTo("Missing observability");
        assertThat(finding.getFixExample()).isEqualTo("logger.info(...)");
        assertThat(finding.getIsBlocking()).isTrue();
        assertThat(finding.getReviewDimension()).isEqualTo("PROJECT_RULE");
    }

    @Test
    void persistsCompleteLlmVersionAndRiskCalibrationTrace() {
        ReviewFindingResult findingResult = new ReviewFindingResult(
            "MEDIUM",
            "LLM",
            null,
            "src/App.java",
            null,
            "Reachability could not be verified",
            "Add an integration test",
            "LOW",
            "partial call chain",
            "potential authorization bypass",
            "verifyAuthorization()",
            false,
            "SECURITY",
            "COMMENT",
            "verification_rejected_downgrade",
            "AUTHORIZATION",
            "Only reachable for an internal caller",
            List.of("src/Auth.java"),
            true,
            "REJECTED",
            new FindingProvenance("llm-review-v2", 1, 1, "HIGH", "HIGH")
        );
        ReviewExecutionProvenance execution = new ReviewExecutionProvenance(
            23,
            "review-prompt-v2",
            "review-context-v2",
            "review-schema-v2",
            "high-risk-verifier-v1",
            "server-risk-v2"
        );
        ReviewResult result = ReviewResult.completed(
            "MEDIUM",
            List.of(findingResult),
            "openai",
            "gpt-5.1",
            120,
            "STRICT",
            "versioned prompt",
            100,
            20,
            120,
            new BigDecimal("0.0012"),
            execution
        );

        ReviewFinding finding = mapper.toEntity(43L, findingResult, result);

        assertThat(finding.getDetectorVersion()).isEqualTo("llm-review-v2");
        assertThat(finding.getPromptVersion()).isEqualTo("review-prompt-v2");
        assertThat(finding.getContextVersion()).isEqualTo("review-context-v2");
        assertThat(finding.getSchemaVersion()).isEqualTo("review-schema-v2");
        assertThat(finding.getVerifierVersion()).isEqualTo("high-risk-verifier-v1");
        assertThat(finding.getAggregationVersion()).isEqualTo("server-risk-v2");
        assertThat(finding.getPolicyVersion()).isEqualTo(23);
        assertThat(finding.getLlmProvider()).isEqualTo("openai");
        assertThat(finding.getLlmModel()).isEqualTo("gpt-5.1");
        assertThat(finding.getOriginalSeverity()).isEqualTo("HIGH");
        assertThat(finding.getSeverity()).isEqualTo("MEDIUM");
        assertThat(finding.getOriginalConfidence()).isEqualTo("HIGH");
        assertThat(finding.getConfidence()).isEqualTo("LOW");
        assertThat(finding.getOriginalIsBlocking()).isTrue();
        assertThat(finding.getIsBlocking()).isFalse();
        assertThat(finding.getDowngradeReason()).isEqualTo("verification_rejected_downgrade");
        assertThat(finding.getBlockReason()).isEmpty();
        assertThat(finding.getAnchorType()).isEqualTo("CROSS_FILE");
        assertThat(finding.getRelatedFiles()).isEqualTo("src/Auth.java");
    }
}
