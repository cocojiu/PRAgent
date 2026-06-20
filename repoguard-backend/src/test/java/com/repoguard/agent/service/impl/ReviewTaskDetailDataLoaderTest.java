package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReviewTaskDetailDataLoaderTest {

    private final ChangedFileMapper changedFileMapper = Mockito.mock(ChangedFileMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = Mockito.mock(ReviewFindingMapper.class);
    private final ReviewTimelineQueryService timelineQueryService = Mockito.mock(ReviewTimelineQueryService.class);
    private final ReviewTaskDetailDataLoader loader = new ReviewTaskDetailDataLoader(
        changedFileMapper,
        reviewFindingMapper,
        timelineQueryService
    );

    @Test
    void loadsDetailDataAndSeparatesFindingsFromMissingTests() {
        when(changedFileMapper.selectList(any(Wrapper.class))).thenReturn(List.of(changedFile()));
        when(reviewFindingMapper.selectList(any(Wrapper.class))).thenReturn(List.of(finding(), missingTest()));
        when(timelineQueryService.loadItemsByTaskId(521L)).thenReturn(List.of(
            new ReviewTimelineItem("Review completed", "10:21:00", "done"),
            new ReviewTimelineItem("Review running", "10:20:00", "current")
        ));

        var result = loader.load(521L);

        assertThat(result.changedFiles()).hasSize(1);
        assertThat(result.changedFiles().getFirst().path()).isEqualTo("src/SecurityConfig.java");
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().severity()).isEqualTo("high");
        assertThat(result.findings().getFirst().feedbackStatus()).isEqualTo("unreviewed");
        assertThat(result.findings().getFirst().feedbackAt()).isEqualTo("2026-06-19 10:20:00");
        assertThat(result.missingTests()).hasSize(1);
        assertThat(result.missingTests().getFirst().method()).isEqualTo("authorize");
        assertThat(result.timeline()).extracting("label").containsExactly("Review completed", "Review running");
        assertThat(result.timeline()).extracting("status").containsExactly("done", "current");
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
