package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewTaskDetailFindingAssemblerTest {

    private final ReviewTaskDetailFindingAssembler assembler = new ReviewTaskDetailFindingAssembler();

    @Test
    void mapsChangedFilesAndSeparatesFindingsFromMissingTests() {
        ReviewFinding finding = finding();
        ReviewFinding missingTest = missingTest();

        var changedFiles = assembler.toChangedFileDtos(List.of(changedFile()));
        var findingDtos = assembler.toFindingDtos(List.of(finding, missingTest));
        var missingTestDtos = assembler.toMissingTestDtos(List.of(finding, missingTest));

        assertThat(changedFiles).hasSize(1);
        assertThat(changedFiles.getFirst().path()).isEqualTo("src/SecurityConfig.java");
        assertThat(changedFiles.getFirst().changeType()).isEqualTo("modified");
        assertThat(findingDtos).hasSize(1);
        assertThat(findingDtos.getFirst().severity()).isEqualTo("high");
        assertThat(findingDtos.getFirst().confidence()).isEqualTo("HIGH");
        assertThat(findingDtos.getFirst().evidence()).isEqualTo("Rule RG-AUTH-001 hit line 20");
        assertThat(findingDtos.getFirst().impact()).isEqualTo("Unauthorized access");
        assertThat(findingDtos.getFirst().fixExample()).isEqualTo("@RequireRole");
        assertThat(findingDtos.getFirst().isBlocking()).isTrue();
        assertThat(findingDtos.getFirst().reviewDimension()).isEqualTo("SECURITY_RULE");
        assertThat(findingDtos.getFirst().feedbackStatus()).isEqualTo("unreviewed");
        assertThat(findingDtos.getFirst().feedbackAt()).isEqualTo("2026-06-19 10:20:00");
        assertThat(missingTestDtos).hasSize(1);
        assertThat(missingTestDtos.getFirst().method()).isEqualTo("authorize");
    }

    @Test
    void keepsExplicitFeedbackStatusAndDefaultsBlankTextFields() {
        ReviewFinding finding = finding();
        finding.setFeedbackStatus("FALSE_POSITIVE");
        finding.setConfidence(" ");
        finding.setEvidence(null);
        finding.setImpact("");
        finding.setFixExample(null);
        finding.setReviewDimension(" ");
        finding.setIsBlocking(null);
        finding.setFeedbackAt(null);

        var result = assembler.toFindingDtos(List.of(finding)).getFirst();

        assertThat(result.feedbackStatus()).isEqualTo("false_positive");
        assertThat(result.confidence()).isEmpty();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.impact()).isEmpty();
        assertThat(result.fixExample()).isEmpty();
        assertThat(result.reviewDimension()).isEmpty();
        assertThat(result.isBlocking()).isFalse();
        assertThat(result.feedbackAt()).isNull();
    }

    private ChangedFile changedFile() {
        ChangedFile file = new ChangedFile();
        file.setFilePath("src/SecurityConfig.java");
        file.setChangeType("modified");
        file.setAdditions(40);
        file.setDeletions(5);
        return file;
    }

    private ReviewFinding finding() {
        ReviewFinding finding = new ReviewFinding();
        finding.setId(1001L);
        finding.setCategory("FINDING");
        finding.setSeverity("HIGH");
        finding.setFilePath("src/SecurityConfig.java");
        finding.setLineNumber(20);
        finding.setMessage("permission bypass");
        finding.setRecommendation("tighten policy");
        finding.setConfidence("HIGH");
        finding.setEvidence("Rule RG-AUTH-001 hit line 20");
        finding.setImpact("Unauthorized access");
        finding.setFixExample("@RequireRole");
        finding.setIsBlocking(true);
        finding.setReviewDimension("SECURITY_RULE");
        finding.setFeedbackAt(LocalDateTime.of(2026, 6, 19, 10, 20));
        return finding;
    }

    private ReviewFinding missingTest() {
        ReviewFinding finding = new ReviewFinding();
        finding.setCategory("MISSING_TEST");
        finding.setFilePath("src/SecurityConfig.java");
        finding.setMethodName("authorize");
        finding.setTestType("unit");
        finding.setRecommendation("add coverage");
        return finding;
    }
}
