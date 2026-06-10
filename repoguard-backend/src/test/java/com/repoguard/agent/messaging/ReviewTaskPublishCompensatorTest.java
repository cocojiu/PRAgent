package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewTaskPublishCompensatorTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTaskPublisher reviewTaskPublisher = org.mockito.Mockito.mock(ReviewTaskPublisher.class);
    private final RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
    private final ReviewTaskPublishCompensator compensator = new ReviewTaskPublishCompensator(
        reviewTaskMapper,
        reviewTimelineMapper,
        reviewTaskPublisher,
        properties,
        "test-instance"
    );

    @Test
    void compensateMarksTaskQueuedWhenPublishSucceeds() {
        ReviewTask task = task();
        task.setPublishAttempts(1);
        ReviewTimeline latest = new ReviewTimeline();
        latest.setSortOrder(3);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latest);

        compensator.compensate(task);

        verify(reviewTaskPublisher).publish(any(ReviewTaskMessage.class));
        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(reviewTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("QUEUED");
        assertThat(taskCaptor.getValue().getPublishAttempts()).isEqualTo(2);
        assertThat(taskCaptor.getValue().getNextPublishRetryAt()).isNull();
        assertThat(taskCaptor.getValue().getLastPublishError()).isNull();
        assertThat(taskCaptor.getValue().getPublishClaimedAt()).isNull();
        assertThat(taskCaptor.getValue().getPublishClaimedBy()).isNull();

        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(timelineCaptor.capture());
        assertThat(timelineCaptor.getValue().getLabel()).isEqualTo("Message publish recovered");
        assertThat(timelineCaptor.getValue().getStatus()).isEqualTo("CURRENT");
        assertThat(timelineCaptor.getValue().getSortOrder()).isEqualTo(4);
    }

    @Test
    void compensateKeepsTaskPublishFailedWhenPublishStillFails() {
        ReviewTask task = task();
        task.setPublishAttempts(1);
        properties.setPublishCompensationIntervalMs(1000);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1);
        doThrow(new MessagePublishException("publisher confirm timed out"))
            .when(reviewTaskPublisher)
            .publish(any(ReviewTaskMessage.class));

        compensator.compensate(task);

        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(reviewTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("PUBLISH_FAILED");
        assertThat(taskCaptor.getValue().getPublishAttempts()).isEqualTo(2);
        assertThat(taskCaptor.getValue().getNextPublishRetryAt()).isNotNull();
        assertThat(taskCaptor.getValue().getLastPublishError()).contains("publisher confirm timed out");
        assertThat(taskCaptor.getValue().getPublishClaimedAt()).isNull();
        assertThat(taskCaptor.getValue().getPublishClaimedBy()).isNull();

        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(timelineCaptor.capture());
        assertThat(timelineCaptor.getValue().getLabel()).contains("Message publish retry failed");
        assertThat(timelineCaptor.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void compensateSkipsPublishWhenClaimFails() {
        ReviewTask task = task();
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(0);

        compensator.compensate(task);

        verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));
        verify(reviewTaskMapper, never()).updateById(any(ReviewTask.class));
        verify(reviewTimelineMapper, never()).insert(any(ReviewTimeline.class));
    }

    @Test
    void compensateClaimUsesLeaseAwareConditionalUpdate() {
        ReviewTask task = task();
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(0);

        compensator.compensate(task);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(reviewTaskMapper).update(wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment)
            .contains("publish_claimed_at")
            .contains("status")
            .contains("next_publish_retry_at")
            .contains("publish_attempts");
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setOrganization("octocat");
        task.setRepository("Hello-World");
        task.setPrNumber(1);
        task.setCommitSha("abc123");
        task.setStatus("PUBLISH_FAILED");
        task.setLlmStatus("PENDING");
        task.setNextPublishRetryAt(LocalDateTime.now().minusMinutes(1));
        return task;
    }
}
