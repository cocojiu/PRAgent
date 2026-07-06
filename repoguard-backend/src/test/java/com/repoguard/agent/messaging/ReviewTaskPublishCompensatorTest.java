package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

class ReviewTaskPublishCompensatorTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTaskPublisher reviewTaskPublisher = org.mockito.Mockito.mock(ReviewTaskPublisher.class);
    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
    private final ReviewTaskStateMachine reviewTaskStateMachine = new ReviewTaskStateMachine();
    private final ReviewTaskPublishOutboxStore outboxStore = new ReviewTaskPublishOutboxStore(
        reviewTaskMapper,
        reviewTimelineMapper,
        reviewTaskStateMachine
    );
    private final ReviewTaskPublishCompensator compensator = new ReviewTaskPublishCompensator(
        reviewTaskMapper,
        reviewTimelineMapper,
        reviewTaskPublisher,
        properties,
        "test-instance",
        metrics,
        outboxStore,
        reviewTaskStateMachine,
        null
    );

    @Test
    void outboxStoreRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new ReviewTaskPublishOutboxStore(
            reviewTaskMapper,
            reviewTimelineMapper,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }

    @Test
    void compensatorRejectsMissingOutboxStore() {
        assertThatThrownBy(() -> new ReviewTaskPublishCompensator(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewTaskPublisher,
            properties,
            "test-instance",
            metrics,
            null,
            reviewTaskStateMachine,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("outboxStore");
    }

    @Test
    void compensatorRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new ReviewTaskPublishCompensator(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewTaskPublisher,
            properties,
            "test-instance",
            metrics,
            outboxStore,
            null,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }

    @Test
    void compensateMarksTaskQueuedWhenPublishSucceeds() {
        ReviewTask task = task();
        task.setPublishAttempts(1);
        ReviewTimeline latest = new ReviewTimeline();
        latest.setSortOrder(3);
        List<String> events = new ArrayList<>();
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenAnswer(invocation -> {
            events.add("database");
            return 1;
        });
        doAnswer(invocation -> {
            events.add("publish");
            return null;
        }).when(reviewTaskPublisher).publish(any(ReviewTaskMessage.class));
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latest);

        compensator.compensate(task);

        assertThat(events).containsExactly("database", "database", "publish", "database");
        verify(reviewTaskMapper, never()).updateById(any(ReviewTask.class));
        assertThat(task.getStatus()).isEqualTo("QUEUED");
        assertThat(task.getPublishAttempts()).isEqualTo(2);
        assertThat(task.getNextPublishRetryAt()).isNull();
        assertThat(task.getLastPublishError()).isNull();
        assertLogContextCleared();

        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(timelineCaptor.capture());
        assertThat(timelineCaptor.getValue().getLabel()).isEqualTo("Message publish recovered");
        assertThat(timelineCaptor.getValue().getStatus()).isEqualTo("CURRENT");
        assertThat(timelineCaptor.getValue().getSortOrder()).isEqualTo(4);
        verify(metrics).rabbitPublishCompensationSucceeded("publish");
    }

    @Test
    void compensateKeepsTaskPublishFailedWhenPublishStillFails() {
        ReviewTask task = task();
        task.setPublishAttempts(1);
        properties.setPublishCompensationIntervalMs(1000);
        List<String> events = new ArrayList<>();
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenAnswer(invocation -> {
            events.add("database");
            return 1;
        });
        doAnswer(invocation -> {
            events.add("publish");
            throw new MessagePublishException("publisher confirm timed out password=raw-password token=raw-token");
        }).when(reviewTaskPublisher).publish(any(ReviewTaskMessage.class));

        compensator.compensate(task);

        assertThat(events).containsExactly("database", "database", "publish", "database");
        verify(reviewTaskMapper, never()).updateById(any(ReviewTask.class));
        assertThat(task.getStatus()).isEqualTo("PUBLISH_FAILED");
        assertThat(task.getPublishAttempts()).isEqualTo(2);
        assertThat(task.getNextPublishRetryAt()).isNotNull();
        assertThat(task.getLastPublishError()).contains("publisher confirm timed out");
        assertThat(task.getLastPublishError()).contains("password=****", "token=****");
        assertThat(task.getLastPublishError()).doesNotContain("raw-password", "raw-token");
        assertThat(task.getPublishClaimedAt()).isNull();
        assertThat(task.getPublishClaimedBy()).isNull();
        assertLogContextCleared();

        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(timelineCaptor.capture());
        assertThat(timelineCaptor.getValue().getLabel()).contains("Message publish retry failed");
        assertThat(timelineCaptor.getValue().getLabel()).contains("password=****", "token=****");
        assertThat(timelineCaptor.getValue().getLabel()).doesNotContain("raw-password", "raw-token");
        assertThat(timelineCaptor.getValue().getStatus()).isEqualTo("FAILED");
        verify(metrics).rabbitPublishCompensationFailed("confirm_timeout");
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
    void compensateDoesNotOverwriteConsumerWhenAmbiguousPublishFailureWasAlreadyConsumed() {
        ReviewTask task = task();
        task.setPublishAttempts(1);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1, 1, 0);
        doThrow(new MessagePublishException("publisher confirm timed out"))
            .when(reviewTaskPublisher)
            .publish(any(ReviewTaskMessage.class));

        compensator.compensate(task);

        assertThat(task.getStatus()).isEqualTo("QUEUED");
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

    @Test
    void compensateCanReclaimQueuedTaskLeftByCrashBeforePublish() {
        ReviewTask task = task();
        task.setStatus("QUEUED");
        task.setPublishAttempts(1);
        task.setPublishClaimedAt(LocalDateTime.now().minusMinutes(5));
        task.setPublishClaimedBy("dead-instance");
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1, 1, 1);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(null);

        compensator.compensate(task);

        verify(reviewTaskPublisher).publish(any(ReviewTaskMessage.class));
        assertThat(task.getStatus()).isEqualTo("QUEUED");
        assertThat(task.getPublishAttempts()).isEqualTo(2);
    }

    @Test
    void compensateCanRecoverQueuedTaskNeverClaimedBeforeCrash() {
        ReviewTask task = task();
        task.setStatus("QUEUED");
        task.setPublishAttempts(0);
        task.setPublishClaimedAt(null);
        task.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1, 1, 1);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(null);

        compensator.compensate(task);

        verify(reviewTaskPublisher).publish(any(ReviewTaskMessage.class));
        assertThat(task.getStatus()).isEqualTo("QUEUED");
        assertThat(task.getPublishAttempts()).isEqualTo(1);
        assertThat(task.getPublishClaimedAt()).isNull();
        assertThat(task.getPublishClaimedBy()).isNull();
    }

    @Test
    void compensateDoesNotReclaimQueuedTaskAtMaxAttempts() {
        properties.setPublishCompensationMaxAttempts(3);
        ReviewTask task = task();
        task.setStatus("QUEUED");
        task.setPublishAttempts(3);
        task.setPublishClaimedAt(LocalDateTime.now().minusMinutes(5));
        task.setPublishClaimedBy("dead-instance");
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(0);

        compensator.compensate(task);

        verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));
        assertThat(task.getPublishAttempts()).isEqualTo(3);
        assertThat(task.getStatus()).isEqualTo("QUEUED");
    }

    @Test
    void compensateDoesNotRetryPublishFailedTaskAtMaxAttempts() {
        properties.setPublishCompensationMaxAttempts(3);
        ReviewTask task = task();
        task.setPublishAttempts(3);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(0);

        compensator.compensate(task);

        verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(reviewTaskMapper).update(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("publish_attempts")
            .contains("<");
    }

    @Test
    void compensationQueryLimitsFailedAndStaleQueuedAttempts() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of());

        compensator.compensatePublishFailures();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reviewTaskMapper).selectList(wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        int attemptsLimit = sqlSegment.indexOf("publish_attempts");
        int staleQueuedBranch = sqlSegment.indexOf("OR");
        assertThat(attemptsLimit).isGreaterThanOrEqualTo(0);
        assertThat(staleQueuedBranch).isGreaterThan(attemptsLimit);
        assertThat(sqlSegment.indexOf("publish_attempts", staleQueuedBranch)).isGreaterThan(staleQueuedBranch);
        assertThat(sqlSegment).contains("created_at");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
            .contains("PUBLISH_FAILED")
            .doesNotContain("EXECUTION_TIMEOUT");
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

    private void assertLogContextCleared() {
        assertThat(MDC.get(LogContext.TASK_ID)).isNull();
        assertThat(MDC.get(LogContext.REPOSITORY)).isNull();
        assertThat(MDC.get(LogContext.PR_NUMBER)).isNull();
    }
}
