package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewTaskExecutorImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTaskExecutorImpl executor = new ReviewTaskExecutorImpl(reviewTaskMapper, reviewTimelineMapper);

    @Test
    void executeMovesQueuedTaskToCompletedAndWritesTimeline() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("QUEUED");
        task.setRiskLevel("INFO");
        task.setLlmStatus("PENDING");
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);

        executor.execute(message());

        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        assertThat(task.getLlmStatus()).isEqualTo("COMPLETED");
        assertThat(task.getStartedAt()).isNotNull();
        assertThat(task.getFinishedAt()).isNotNull();
        assertThat(task.getDurationSeconds()).isNotNull();
        verify(reviewTaskMapper, org.mockito.Mockito.times(2)).updateById(task);
        verify(reviewTimelineMapper, org.mockito.Mockito.times(2)).insert(any(ReviewTimeline.class));
    }

    @Test
    void executeIgnoresCompletedTask() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("COMPLETED");
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);

        executor.execute(message());

        verify(reviewTaskMapper, never()).updateById(any(ReviewTask.class));
        verify(reviewTimelineMapper, never()).insert(any(ReviewTimeline.class));
    }

    private ReviewTaskMessage message() {
        return new ReviewTaskMessage(
            42L,
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            "a1b2c3d",
            LocalDateTime.parse("2026-06-05T18:00:00")
        );
    }
}
