package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.review.ReviewTaskDetailAssembler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewTaskQueryServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
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
    void listRepositoriesReadsRepositoryDimensionLabels() {
        when(repositoryDimensionService.listRepositoryLabels())
            .thenReturn(List.of("org-a/repo-guard", "org-b/repo-guard"));

        var repositories = service().listRepositories();

        org.assertj.core.api.Assertions.assertThat(repositories)
            .containsExactly("org-a/repo-guard", "org-b/repo-guard");
        verify(repositoryDimensionService).listRepositoryLabels();
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

    private ReviewTaskQueryServiceImpl service() {
        return new ReviewTaskQueryServiceImpl(
            reviewTaskMapper,
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
