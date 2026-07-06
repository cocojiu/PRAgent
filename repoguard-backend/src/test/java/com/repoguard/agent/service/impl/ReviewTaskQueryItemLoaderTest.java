package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewTaskQueryItemLoaderTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTaskQueryItemLoader loader = new ReviewTaskQueryItemLoader(
        reviewTaskMapper,
        new ReviewFailureSummaryResolver(),
        new ReviewTimelineQueryService(reviewTimelineMapper),
        new ReviewTaskListItemAssembler()
    );

    @Test
    void loadRequiredThrowsReadableExceptionWhenTaskMissing() {
        when(reviewTaskMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> loader.loadRequired(404L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Review task not found: 404");
    }

    @Test
    void assemblesListItemWithFailureSummaryFromTimeline() {
        ReviewTask task = task();
        task.setStatus("FAILED");
        ReviewTimeline timeline = timeline("Review failed: category=github_token_invalid");

        var item = loader.assemble(task, List.of(timeline));

        assertThat(item.status()).isEqualTo("failed");
        assertThat(item.failureCategory()).isEqualTo("github_token_invalid");
        assertThat(item.failureReason()).isEqualTo("GitHub Token 无效或已过期");
        assertThat(item.failureSuggestion()).contains("更新 GitHub Token");
    }

    @Test
    void loadsTimelinesAndLatestTimelineItemForStatusSnapshot() {
        ReviewTimeline first = timeline("Queued");
        first.setSortOrder(1);
        first.setStatus("DONE");
        ReviewTimeline second = timeline("Reviewing");
        second.setSortOrder(2);
        second.setStatus("CURRENT");
        when(reviewTimelineMapper.selectList(any())).thenReturn(List.of(first, second));

        List<ReviewTimeline> timelines = loader.loadTimelines(521L);
        var latest = loader.latestTimelineItem(timelines);

        assertThat(timelines).hasSize(2);
        assertThat(latest.label()).isEqualTo("Reviewing");
        assertThat(latest.status()).isEqualTo("current");
    }

    @Test
    void constructorRejectsMissingListItemAssembler() {
        assertThatThrownBy(() -> new ReviewTaskQueryItemLoader(
            reviewTaskMapper,
            new ReviewFailureSummaryResolver(),
            new ReviewTimelineQueryService(reviewTimelineMapper),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("listItemAssembler");
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(521L);
        task.setPrNumber(42);
        task.setTitle("Review task");
        task.setRepository("Hello-World");
        task.setOrganization("octocat");
        task.setCommitSha("abc123");
        task.setBranchName("main");
        task.setMqRetries(0);
        task.setRiskLevel("HIGH");
        task.setLlmStatus("FAILED");
        task.setSource("MANUAL_INPUT");
        task.setTriggerSource("MANUAL_INPUT");
        task.setCreatedAt(LocalDateTime.of(2026, 6, 22, 17, 40));
        task.setHumanReviewRequired(false);
        return task;
    }

    private ReviewTimeline timeline(String label) {
        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(521L);
        timeline.setLabel(label);
        timeline.setStatus("FAILED");
        timeline.setEventTime(LocalDateTime.of(2026, 6, 22, 17, 45));
        return timeline;
    }
}
