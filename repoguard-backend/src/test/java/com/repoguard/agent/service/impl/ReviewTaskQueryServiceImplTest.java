package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskListSummary;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ReviewTaskArchiveSummary;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskArchiveSummaryMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.review.ReviewTaskDetailAssembler;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

class ReviewTaskQueryServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTaskArchiveSummaryMapper archiveSummaryMapper =
        org.mockito.Mockito.mock(ReviewTaskArchiveSummaryMapper.class);
    private final ReviewTaskDetailAssembler detailAssembler = new ReviewTaskDetailAssembler(
        new ReviewRiskProfileBuilder(),
        new PrReviewSummaryBuilder()
    );
    private final ReviewTaskDetailDataLoader detailDataLoader =
        org.mockito.Mockito.mock(ReviewTaskDetailDataLoader.class);
    private final ReviewTaskQueryItemLoader queryItemLoader =
        org.mockito.Mockito.mock(ReviewTaskQueryItemLoader.class);
    private final ReviewTaskStatusAssembler statusAssembler = new ReviewTaskStatusAssembler();
    private final ReviewTaskListQueryBuilder listQueryBuilder = new ReviewTaskListQueryBuilder();
    private final ReviewRepositoryDimensionService repositoryDimensionService =
        org.mockito.Mockito.mock(ReviewRepositoryDimensionService.class);

    @Test
    void constructorRejectsMissingDetailDataLoader() {
        assertThatThrownBy(() -> new ReviewTaskQueryServiceImpl(
            reviewTaskMapper,
            archiveSummaryMapper,
            detailAssembler,
            null,
            queryItemLoader,
            statusAssembler,
            listQueryBuilder,
            repositoryDimensionService
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("detailDataLoader");
    }

    @Test
    void constructorRejectsMissingQueryItemLoader() {
        assertThatThrownBy(() -> new ReviewTaskQueryServiceImpl(
            reviewTaskMapper,
            archiveSummaryMapper,
            detailAssembler,
            detailDataLoader,
            null,
            statusAssembler,
            listQueryBuilder,
            repositoryDimensionService
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("queryItemLoader");
    }

    @Test
    void getReviewDetailFallsBackToArchiveSummaryWhenHotTaskWasCleaned() {
        ReviewTaskArchiveSummary archive = archiveSummary(521L);
        when(reviewTaskMapper.selectById(521L)).thenReturn(null);
        when(archiveSummaryMapper.selectByTaskId(521L)).thenReturn(archive);

        var response = service().getReviewDetail(521L);

        org.assertj.core.api.Assertions.assertThat(response.id()).isEqualTo(521L);
        org.assertj.core.api.Assertions.assertThat(response.archived()).isTrue();
        org.assertj.core.api.Assertions.assertThat(response.archiveCleanupBatchId()).isEqualTo(3001L);
        org.assertj.core.api.Assertions.assertThat(response.archiveBackupReference()).isEqualTo("backup://mysql/prod/2026-07-08");
        org.assertj.core.api.Assertions.assertThat(response.archivedAt()).isEqualTo("2026-07-09 01:10:00");
        org.assertj.core.api.Assertions.assertThat(response.findingTotal()).isEqualTo(6);
        org.assertj.core.api.Assertions.assertThat(response.missingTestTotal()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(response.changedFileTotal()).isEqualTo(12);
        org.assertj.core.api.Assertions.assertThat(response.findings()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(response.changedFiles()).isEmpty();
        verify(queryItemLoader, never()).loadRequired(521L);
        verify(detailDataLoader, never()).loadSummary(521L);
    }

    @Test
    void getReviewDetailStillRejectsMissingTaskWhenArchiveSummaryIsAbsent() {
        when(reviewTaskMapper.selectById(404L)).thenReturn(null);
        when(archiveSummaryMapper.selectByTaskId(404L)).thenReturn(null);

        assertThatThrownBy(() -> service().getReviewDetail(404L))
            .isInstanceOf(com.repoguard.agent.common.BusinessException.class)
            .hasMessageContaining("Review task not found: 404");
    }

    @Test
    void getReviewDetailReturnsSummaryWithoutLoadingHeavySections() {
        ReviewTask task = reviewTask(521L);
        ReviewTaskListItem item = listItem(521L);
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);
        when(detailDataLoader.loadSummary(521L)).thenReturn(new ReviewTaskDetailDataLoader.ReviewTaskDetailData(
            List.of(new ChangedFileDto("src/App.java", "modified", 10, 2)),
            List.of(new ReviewFindingDto(
                1L,
                "high",
                "src/App.java",
                12,
                "Use logger",
                "Replace stdout with logger",
                "HIGH",
                "System.out.println(password)",
                "Secret may leak",
                "log.info(\"user exported\")",
                true,
                "security",
                "valid",
                null,
                null,
                null
            )),
            List.of(new MissingTestDto("UserExportControllerTest", "exportUsers", "controller", "Add test")),
            List.of(new ReviewTimelineItem("Heavy timeline item", "10:20:00", "done")),
            42L,
            7L,
            3L,
            new FindingSeverityCountsDto(1L, 2L, 3L, 4L, 5L)
        ));
        when(detailDataLoader.loadTimelineItems(521L, 20))
            .thenReturn(List.of(new ReviewTimelineItem("Review completed", "10:21:00", "done")));
        when(queryItemLoader.assembleFromTimelineItems(task, List.of(new ReviewTimelineItem(
            "Review completed",
            "10:21:00",
            "done"
        )))).thenReturn(item);

        var response = service().getReviewDetail(521L);

        org.assertj.core.api.Assertions.assertThat(response.id()).isEqualTo(521L);
        org.assertj.core.api.Assertions.assertThat(response.findings()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(response.missingTests()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(response.changedFiles()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(response.timeline()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(response.findingTotal()).isEqualTo(7);
        org.assertj.core.api.Assertions.assertThat(response.missingTestTotal()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(response.changedFileTotal()).isEqualTo(42);
        verify(detailDataLoader).loadSummary(521L);
        verify(detailDataLoader, never()).loadFindingsPage(any(), anyInt(), anyInt(), any(), any(), any());
        verify(detailDataLoader, never()).loadChangedFilesPage(any(), anyInt(), anyInt(), any());
        verify(detailDataLoader, never()).loadMissingTestsPage(any(), anyInt(), anyInt());
    }

    @Test
    void archivedDetailPagedSectionsReturnArchivedTotalsWithoutHotDetailQueries() {
        ReviewTaskArchiveSummary archive = archiveSummary(521L);
        when(reviewTaskMapper.selectById(521L)).thenReturn(null);
        when(archiveSummaryMapper.selectByTaskId(521L)).thenReturn(archive);

        var findings = service().listReviewFindings(521L, 1, 20, null, null, null);
        var filteredFindings = service().listReviewFindings(521L, 1, 20, "high", null, null);
        var changedFiles = service().listChangedFiles(521L, 1, 20, null);
        var filteredChangedFiles = service().listChangedFiles(521L, 1, 20, true);
        var missingTests = service().listMissingTests(521L, 1, 20);

        org.assertj.core.api.Assertions.assertThat(findings.items()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(findings.total()).isEqualTo(6);
        org.assertj.core.api.Assertions.assertThat(filteredFindings.total()).isZero();
        org.assertj.core.api.Assertions.assertThat(changedFiles.items()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(changedFiles.total()).isEqualTo(12);
        org.assertj.core.api.Assertions.assertThat(filteredChangedFiles.total()).isZero();
        org.assertj.core.api.Assertions.assertThat(missingTests.items()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(missingTests.total()).isEqualTo(2);
        verify(detailDataLoader, never()).loadFindingsPage(any(), anyInt(), anyInt(), any(), any(), any());
        verify(detailDataLoader, never()).loadChangedFilesPage(any(), anyInt(), anyInt(), any());
        verify(detailDataLoader, never()).loadMissingTestsPage(any(), anyInt(), anyInt());
    }

    @Test
    void archivedTimelineAndStatusReturnArchiveMarker() {
        ReviewTaskArchiveSummary archive = archiveSummary(521L);
        when(reviewTaskMapper.selectById(521L)).thenReturn(null);
        when(archiveSummaryMapper.selectByTaskId(521L)).thenReturn(archive);

        var timeline = service().listReviewTimeline(521L, 20);
        var status = service().getReviewStatus(521L);

        org.assertj.core.api.Assertions.assertThat(timeline)
            .containsExactly(new com.repoguard.agent.dto.ReviewTimelineItem(
                "Review task archived; summary restored from retention archive",
                "2026-07-09 01:10:00",
                "done"
            ));
        org.assertj.core.api.Assertions.assertThat(status.id()).isEqualTo(521L);
        org.assertj.core.api.Assertions.assertThat(status.status()).isEqualTo("completed");
        org.assertj.core.api.Assertions.assertThat(status.latestTimeline().label()).contains("archived");
        verify(detailDataLoader, never()).loadTimelineItems(521L, 20);
        verify(queryItemLoader, never()).loadTimelines(521L);
    }

    @Test
    void listRepositoriesReadsRepositoryDimensionLabels() {
        when(repositoryDimensionService.listRepositoryLabels())
            .thenReturn(List.of("org-a/repo-guard", "org-b/repo-guard"));

        var repositories = service().listRepositories();

        org.assertj.core.api.Assertions.assertThat(repositories)
            .containsExactly("org-a/repo-guard", "org-b/repo-guard");
        verify(repositoryDimensionService).listRepositoryLabels();
    }

    @Test
    void getReviewListSummaryAggregatesThroughSharedFilterQuery() {
        ReviewTaskMapper.ReviewTaskListSummaryStat stat = new ReviewTaskMapper.ReviewTaskListSummaryStat();
        stat.setTotal(321L);
        stat.setHighRisk(12L);
        stat.setFailed(7L);
        stat.setAverageDurationSeconds(new BigDecimal("95.5"));
        when(reviewTaskMapper.selectListSummaryStat(any(Wrapper.class))).thenReturn(stat);

        var summary = service().getReviewListSummary(new ReviewQuery(1, 1, null, null, null, null, null, null));

        org.assertj.core.api.Assertions.assertThat(summary)
            .isEqualTo(new ReviewTaskListSummary(321L, 12L, 7L, 96L));
        verify(reviewTaskMapper).selectListSummaryStat(any(Wrapper.class));
        verify(reviewTaskMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
        verify(reviewTaskMapper, never()).selectCount(any(Wrapper.class));
    }

    @Test
    void getReviewListSummaryReturnsZeroesWhenAggregateRowIsMissing() {
        when(reviewTaskMapper.selectListSummaryStat(any(Wrapper.class))).thenReturn(null);

        var summary = service().getReviewListSummary(new ReviewQuery(1, 1, null, null, null, null, null, null));

        org.assertj.core.api.Assertions.assertThat(summary)
            .isEqualTo(new ReviewTaskListSummary(0L, 0L, 0L, 0L));
    }

    @Test
    void getReviewListSummaryUsesSynchronizedFilterKeyedCache() throws Exception {
        Cacheable cacheable = ReviewTaskQueryServiceImpl.class
            .getMethod("getReviewListSummary", ReviewQuery.class)
            .getAnnotation(Cacheable.class);

        org.assertj.core.api.Assertions.assertThat(cacheable).isNotNull();
        org.assertj.core.api.Assertions.assertThat(cacheable.cacheNames())
            .containsExactly(CacheNames.REVIEW_TASK_LIST_SUMMARY);
        org.assertj.core.api.Assertions.assertThat(cacheable.sync()).isTrue();
        org.assertj.core.api.Assertions.assertThat(cacheable.key()).isEqualTo("#query.listSummaryCacheKey()");
        org.assertj.core.api.Assertions.assertThat(
            new ReviewQuery(1, 1, " org/repo ", "failed", null, null, null, null).listSummaryCacheKey()
        ).isEqualTo(new ReviewQuery.ReviewListSummaryCacheKey(
            "org/repo",
            "FAILED",
            null,
            null,
            null,
            null
        ));
        org.assertj.core.api.Assertions.assertThat(
            new ReviewQuery(1, 1, "a|b", "c", null, null, null, null).listSummaryCacheKey()
        ).isNotEqualTo(
            new ReviewQuery(1, 1, "a", "b|c", null, null, null, null).listSummaryCacheKey()
        );
        org.assertj.core.api.Assertions.assertThat(
            new ReviewQuery(1, 1, " org/repo ", " failed ", null, null, null, " keyword ")
                .listSummaryCacheKey()
        ).isEqualTo(
            new ReviewQuery(1, 1, "org/repo", "FAILED", " ", null, null, "keyword")
                .listSummaryCacheKey()
        );
    }

    @Test
    void listReviewsUsesOffsetPageWhenCursorIsAbsent() {
        ReviewTask task = reviewTask(7L);
        Page<ReviewTask> page = Page.of(1, 20);
        page.setRecords(List.of(task));
        page.setTotal(1);
        ReviewTaskListItem item = listItem(task.getId());
        when(reviewTaskMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
        when(queryItemLoader.loadTimelinesByTaskId(List.of(task))).thenReturn(Map.of(task.getId(), List.of()));
        when(queryItemLoader.assemble(task, List.of())).thenReturn(item);

        var response = service().listReviews(new ReviewQuery(1, 20, null, null, null, null, null, null));

        org.assertj.core.api.Assertions.assertThat(response.items()).containsExactly(item);
        org.assertj.core.api.Assertions.assertThat(response.total()).isEqualTo(1);
        verify(reviewTaskMapper).selectPage(any(Page.class), any(Wrapper.class));
        verify(reviewTaskMapper, never()).selectList(any(Wrapper.class));
        verify(reviewTaskMapper, never()).selectCount(any(Wrapper.class));
    }

    @Test
    void listReviewsUsesKeysetQueryAndFilterCountWhenCursorIsPresent() {
        ReviewTask task = reviewTask(8L);
        ReviewTaskListItem item = listItem(task.getId());
        when(reviewTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(task));
        when(reviewTaskMapper.selectCount(any(Wrapper.class))).thenReturn(42L);
        when(queryItemLoader.loadTimelinesByTaskId(List.of(task))).thenReturn(Map.of(task.getId(), List.of()));
        when(queryItemLoader.assemble(task, List.of())).thenReturn(item);

        var response = service().listReviews(new ReviewQuery(
            3,
            20,
            null,
            null,
            null,
            null,
            null,
            null,
            "2026-07-08 12:00:00",
            task.getId()
        ));

        org.assertj.core.api.Assertions.assertThat(response.items()).containsExactly(item);
        org.assertj.core.api.Assertions.assertThat(response.total()).isEqualTo(42);
        verify(reviewTaskMapper).selectList(any(Wrapper.class));
        verify(reviewTaskMapper).selectCount(any(Wrapper.class));
        verify(reviewTaskMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void listReviewsUsesKeysetTotalHintWithoutCountingAgain() {
        ReviewTask task = reviewTask(8L);
        ReviewTaskListItem item = listItem(task.getId());
        when(reviewTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(task));
        when(queryItemLoader.loadTimelinesByTaskId(List.of(task))).thenReturn(Map.of(task.getId(), List.of()));
        when(queryItemLoader.assemble(task, List.of())).thenReturn(item);

        var response = service().listReviews(new ReviewQuery(
            3,
            20,
            null,
            null,
            null,
            null,
            null,
            null,
            "2026-07-08 12:00:00",
            task.getId(),
            42L
        ));

        org.assertj.core.api.Assertions.assertThat(response.items()).containsExactly(item);
        org.assertj.core.api.Assertions.assertThat(response.total()).isEqualTo(42);
        verify(reviewTaskMapper).selectList(any(Wrapper.class));
        verify(reviewTaskMapper, never()).selectCount(any(Wrapper.class));
        verify(reviewTaskMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    private ReviewTaskQueryServiceImpl service() {
        return new ReviewTaskQueryServiceImpl(
            reviewTaskMapper,
            archiveSummaryMapper,
            detailAssembler,
            detailDataLoader,
            queryItemLoader,
            statusAssembler,
            listQueryBuilder,
            repositoryDimensionService
        );
    }

    private ReviewTask reviewTask(Long id) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        task.setPrNumber(42);
        task.setTitle("Task " + id);
        task.setRepository("repo");
        task.setOrganization("org");
        task.setCommitSha("abcdef0");
        task.setBranchName("main");
        task.setStatus("COMPLETED");
        task.setRiskLevel("LOW");
        task.setMqRetries(0);
        task.setCreatedAt(LocalDateTime.of(2026, 7, 8, 12, 0));
        return task;
    }

    private ReviewTaskArchiveSummary archiveSummary(Long taskId) {
        ReviewTaskArchiveSummary archive = new ReviewTaskArchiveSummary();
        archive.setTaskId(taskId);
        archive.setCleanupBatchId(3001L);
        archive.setOrganization("org");
        archive.setRepository("repo");
        archive.setPrNumber(42);
        archive.setTitle("Task " + taskId);
        archive.setCommitSha("abcdef0");
        archive.setBranchName("main");
        archive.setStatus("COMPLETED");
        archive.setRiskLevel("LOW");
        archive.setSource("manual_input");
        archive.setTriggerSource("manual_input");
        archive.setCreatedAt(LocalDateTime.of(2026, 6, 8, 12, 0));
        archive.setFinishedAt(LocalDateTime.of(2026, 6, 8, 12, 3));
        archive.setDurationSeconds(180);
        archive.setFindingCount(6);
        archive.setMissingTestCount(2);
        archive.setChangedFileCount(12);
        archive.setTimelineCount(4);
        archive.setPublicationCount(3);
        archive.setBackupReference("backup://mysql/prod/2026-07-08");
        archive.setArchivedAt(LocalDateTime.of(2026, 7, 9, 1, 10));
        return archive;
    }

    private ReviewTaskListItem listItem(Long id) {
        return new ReviewTaskListItem(
            id,
            42,
            "Task " + id,
            "repo",
            "org",
            "abcdef0",
            "main",
            "completed",
            "low",
            0,
            "completed",
            "manual_input",
            "manual_input",
            "2026-07-08 12:00:00",
            "1 秒",
            null,
            null,
            null,
            false,
            "not_required",
            null,
            null,
            null
        );
    }
}
