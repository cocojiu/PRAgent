package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GithubCommentPreviewDataLoaderTest {

    private final ChangedFileMapper changedFileMapper = Mockito.mock(ChangedFileMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = Mockito.mock(ReviewFindingMapper.class);
    private final GithubCommentPreviewDataLoader loader = new GithubCommentPreviewDataLoader(
        changedFileMapper,
        reviewFindingMapper
    );

    @Test
    void loadsPreviewDataAndSeparatesActionableFindingsFromMissingTests() {
        when(changedFileMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            changedFile(2L, "src/App.java"),
            changedFile(1L, "README.md")
        ));
        ReviewFinding actionable = finding(1001L, "FINDING", "HIGH", "README.md", 8);
        actionable.setFeedbackAt(LocalDateTime.of(2026, 6, 19, 10, 30));
        ReviewFinding missingTest = finding(1002L, "MISSING_TEST", "LOW", "src/App.java", null);
        missingTest.setMethodName("review");
        missingTest.setTestType("unit");
        when(reviewFindingMapper.selectList(any(Wrapper.class))).thenReturn(List.of(actionable, missingTest));

        var result = loader.load(521L);

        assertThat(result.changedFileByPath()).containsOnlyKeys("README.md", "src/App.java");
        assertThat(result.changedFiles()).extracting("path").containsExactly("README.md", "src/App.java");
        assertThat(result.actionableFindings()).containsExactly(actionable);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().severity()).isEqualTo("high");
        assertThat(result.findings().getFirst().feedbackStatus()).isEqualTo("unreviewed");
        assertThat(result.findings().getFirst().feedbackAt()).isEqualTo("2026-06-19 10:30:00");
        assertThat(result.missingTests()).hasSize(1);
        assertThat(result.missingTests().getFirst().method()).isEqualTo("review");
    }

    private ChangedFile changedFile(Long id, String path) {
        ChangedFile file = new ChangedFile();
        file.setId(id);
        file.setTaskId(521L);
        file.setFilePath(path);
        file.setChangeType("MODIFY");
        file.setAdditions(6);
        file.setDeletions(1);
        return file;
    }

    private ReviewFinding finding(Long id, String category, String severity, String file, Integer line) {
        ReviewFinding finding = new ReviewFinding();
        finding.setId(id);
        finding.setTaskId(521L);
        finding.setCategory(category);
        finding.setSeverity(severity);
        finding.setFilePath(file);
        finding.setLineNumber(line);
        finding.setMessage("message");
        finding.setRecommendation("recommendation");
        return finding;
    }
}
