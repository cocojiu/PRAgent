package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.review.ReviewFindingResult;
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
}
