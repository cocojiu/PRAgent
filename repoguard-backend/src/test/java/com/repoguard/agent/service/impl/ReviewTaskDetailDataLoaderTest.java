package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
        when(changedFileMapper.selectPage(any(), any())).thenReturn(page(List.of(changedFile())));
        when(reviewFindingMapper.selectPage(any(), any())).thenReturn(
            page(List.of(finding())),
            page(List.of(missingTest()))
        );
        when(timelineQueryService.loadLatestItemsByTaskId(521L, 20)).thenReturn(List.of(
            new ReviewTimelineItem("Review completed", "10:21:00", "done"),
            new ReviewTimelineItem("Review running", "10:20:00", "current")
        ));

        var result = loader.load(521L);

        assertThat(result.changedFiles()).hasSize(1);
        assertThat(result.changedFiles().getFirst().path()).isEqualTo("src/SecurityConfig.java");
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().severity()).isEqualTo("high");
        assertThat(result.findings().getFirst().confidence()).isEqualTo("HIGH");
        assertThat(result.findings().getFirst().evidence()).isEqualTo("Rule RG-AUTH-001 hit line 20");
        assertThat(result.findings().getFirst().impact()).isEqualTo("Unauthorized access");
        assertThat(result.findings().getFirst().fixExample()).isEqualTo("@RequireRole");
        assertThat(result.findings().getFirst().isBlocking()).isTrue();
        assertThat(result.findings().getFirst().reviewDimension()).isEqualTo("SECURITY_RULE");
        assertThat(result.findings().getFirst().feedbackStatus()).isEqualTo("unreviewed");
        assertThat(result.findings().getFirst().feedbackAt()).isEqualTo("2026-06-19 10:20:00");
        assertThat(result.missingTests()).hasSize(1);
        assertThat(result.missingTests().getFirst().method()).isEqualTo("authorize");
        assertThat(result.timeline()).extracting("label").containsExactly("Review completed", "Review running");
        assertThat(result.timeline()).extracting("status").containsExactly("done", "current");
        assertThat(result.changedFileTotal()).isEqualTo(1);
        assertThat(result.findingTotal()).isEqualTo(1);
        assertThat(result.missingTestTotal()).isEqualTo(1);

        ArgumentCaptor<Page> changedFilePageCaptor = ArgumentCaptor.forClass(Page.class);
        Mockito.verify(changedFileMapper).selectPage(changedFilePageCaptor.capture(), any());
        assertInitialDetailPage(changedFilePageCaptor.getValue());

        ArgumentCaptor<Page> findingPageCaptor = ArgumentCaptor.forClass(Page.class);
        Mockito.verify(reviewFindingMapper, Mockito.times(2)).selectPage(findingPageCaptor.capture(), any());
        assertThat(findingPageCaptor.getAllValues()).hasSize(2);
        findingPageCaptor.getAllValues().forEach(this::assertInitialDetailPage);
        Mockito.verify(timelineQueryService).loadLatestItemsByTaskId(521L, 20);
    }

    private <T> Page<T> page(List<T> records) {
        Page<T> page = Page.of(1, 20);
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    private void assertInitialDetailPage(Page<?> page) {
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(20);
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
