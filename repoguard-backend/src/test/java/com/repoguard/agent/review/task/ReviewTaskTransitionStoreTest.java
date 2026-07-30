package com.repoguard.agent.review.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewTaskTransitionStoreTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTaskTransitionStore store = new ReviewTaskTransitionStore(
        reviewTaskMapper,
        new ReviewTaskStateMachine()
    );

    @Test
    void retryUsesFailedCasAndExplicitlyClearsAttemptState() {
        ReviewTask task = staleFailedTask();
        when(reviewTaskMapper.update(any())).thenReturn(1);

        store.retryFailedTask(task, 3);

        ArgumentCaptor<UpdateWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.captor();
        verify(reviewTaskMapper).update(wrapperCaptor.capture());
        UpdateWrapper<ReviewTask> update = wrapperCaptor.getValue();
        assertThat(update.getSqlSegment()).contains("id", "status");
        assertThat(update.getSqlSet()).contains(
            "status",
            "mq_retries",
            "commit_sha",
            "llm_provider",
            "llm_model",
            "llm_duration_ms",
            "llm_parse_status",
            "llm_prompt_tokens",
            "llm_estimated_cost",
            "human_review_note",
            "last_publish_error",
            "publish_claimed_at",
            "review_claimed_at",
            "started_at",
            "finished_at"
        );
        assertThat(update.getParamNameValuePairs().values())
            .contains("FAILED", "QUEUED", "PENDING", "NOT_REQUIRED", 3, 0);
        assertQueuedAndCleared(task);
        assertThat(task.getMqRetries()).isEqualTo(3);
        assertThat(task.getCommitSha()).isEqualTo("old-commit");
        verify(reviewTaskMapper, never()).updateById(any(ReviewTask.class));
    }

    @Test
    void supersededRetryUsesObservedStatusAndCommitFenceThenReplacesCommit() {
        ReviewTask task = staleFailedTask();
        task.setStatus("SUPERSEDED");
        when(reviewTaskMapper.update(any())).thenReturn(1);

        store.retryReviewTask(task, 3, "latest-commit");

        ArgumentCaptor<UpdateWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.captor();
        verify(reviewTaskMapper).update(wrapperCaptor.capture());
        UpdateWrapper<ReviewTask> update = wrapperCaptor.getValue();
        assertThat(update.getSqlSegment()).contains("status", "commit_sha");
        assertThat(update.getSqlSet()).contains("commit_sha", "status", "llm_fallback_reason");
        assertThat(update.getParamNameValuePairs().values())
            .contains("SUPERSEDED", "old-commit", "latest-commit", "QUEUED");
        assertQueuedAndCleared(task);
        assertThat(task.getCommitSha()).isEqualTo("latest-commit");
    }

    @Test
    void retryConflictReturnsConflictAndLeavesSnapshotUntouched() {
        ReviewTask task = staleFailedTask();
        when(reviewTaskMapper.update(any())).thenReturn(0);

        assertThatThrownBy(() -> store.retryFailedTask(task, 3))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT))
            .hasMessage(ReviewTaskTransitionStore.STATE_CHANGED_MESSAGE);

        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getLlmProvider()).isEqualTo("openai");
        assertThat(task.getHumanReviewNote()).isEqualTo("old decision");
        assertThat(task.getPublishClaimedBy()).isEqualTo("old-publisher");
    }

    @Test
    void humanReviewUsesPendingDecisionFence() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("PENDING_HUMAN_REVIEW");
        task.setHumanReviewRequired(true);
        task.setHumanReviewStatus("PENDING");
        LocalDateTime reviewedAt = LocalDateTime.parse("2026-07-28T10:00:00");
        when(reviewTaskMapper.update(any())).thenReturn(1);

        store.completeHumanReview(
            task,
            "APPROVED",
            "APPROVED",
            "looks good",
            "review-lead",
            reviewedAt
        );

        ArgumentCaptor<UpdateWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.captor();
        verify(reviewTaskMapper).update(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("status", "human_review_required", "human_review_status");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
            .contains("PENDING_HUMAN_REVIEW", "PENDING", "APPROVED", "review-lead");
        assertThat(task.getStatus()).isEqualTo("APPROVED");
        assertThat(task.getHumanReviewStatus()).isEqualTo("APPROVED");
        assertThat(task.getHumanReviewedAt()).isEqualTo(reviewedAt);
    }

    @Test
    void manualRequeueChecksObservedStatusAndUnclaimedPublishLease() {
        ReviewTask task = staleFailedTask();
        task.setStatus("EXECUTION_TIMEOUT");
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        when(reviewTaskMapper.update(any())).thenReturn(1);

        store.requeueForPublish(task);

        ArgumentCaptor<UpdateWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.captor();
        verify(reviewTaskMapper).update(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("status", "publish_claimed_at", "IS NULL");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
            .contains("EXECUTION_TIMEOUT", "QUEUED");
        assertQueuedAndCleared(task);
    }

    @Test
    void constructorRequiresMapperAndStateMachine() {
        assertThatThrownBy(() -> new ReviewTaskTransitionStore(null, new ReviewTaskStateMachine()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskMapper");
        assertThatThrownBy(() -> new ReviewTaskTransitionStore(reviewTaskMapper, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }

    private ReviewTask staleFailedTask() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("FAILED");
        task.setCommitSha("old-commit");
        task.setRiskLevel("HIGH");
        task.setMqRetries(2);
        task.setPublishAttempts(4);
        task.setNextPublishRetryAt(LocalDateTime.parse("2026-07-28T10:01:00"));
        task.setLastPublishError("old error");
        task.setPublishClaimedAt(LocalDateTime.parse("2026-07-28T09:59:00"));
        task.setPublishClaimedBy("old-publisher");
        task.setReviewClaimedAt(LocalDateTime.parse("2026-07-28T09:58:00"));
        task.setReviewClaimedBy("old-reviewer");
        task.setLlmStatus("FAILED");
        task.setLlmProvider("openai");
        task.setLlmModel("gpt-old");
        task.setLlmDurationMs(1200);
        task.setLlmParseStatus("parsed");
        task.setLlmFallbackReason("old fallback");
        task.setLlmPromptSummary("old prompt");
        task.setLlmPromptTokens(100);
        task.setLlmCompletionTokens(20);
        task.setLlmTotalTokens(120);
        task.setLlmEstimatedCost(new BigDecimal("0.123456"));
        task.setHumanReviewRequired(true);
        task.setHumanReviewStatus("REJECTED");
        task.setHumanReviewNote("old decision");
        task.setHumanReviewBy("old-reviewer");
        task.setHumanReviewedAt(LocalDateTime.parse("2026-07-28T09:57:00"));
        task.setStartedAt(LocalDateTime.parse("2026-07-28T09:50:00"));
        task.setFinishedAt(LocalDateTime.parse("2026-07-28T09:55:00"));
        task.setDurationSeconds(300);
        return task;
    }

    private void assertQueuedAndCleared(ReviewTask task) {
        assertThat(task.getStatus()).isEqualTo("QUEUED");
        assertThat(task.getRiskLevel()).isEqualTo("INFO");
        assertThat(task.getPublishAttempts()).isZero();
        assertThat(task.getNextPublishRetryAt()).isNull();
        assertThat(task.getLastPublishError()).isNull();
        assertThat(task.getPublishClaimedAt()).isNull();
        assertThat(task.getPublishClaimedBy()).isNull();
        assertThat(task.getReviewClaimedAt()).isNull();
        assertThat(task.getReviewClaimedBy()).isNull();
        assertThat(task.getLlmStatus()).isEqualTo("PENDING");
        assertThat(task.getLlmProvider()).isNull();
        assertThat(task.getLlmModel()).isNull();
        assertThat(task.getLlmDurationMs()).isNull();
        assertThat(task.getLlmParseStatus()).isNull();
        assertThat(task.getLlmFallbackReason()).isNull();
        assertThat(task.getLlmPromptSummary()).isNull();
        assertThat(task.getLlmPromptTokens()).isNull();
        assertThat(task.getLlmCompletionTokens()).isNull();
        assertThat(task.getLlmTotalTokens()).isNull();
        assertThat(task.getLlmEstimatedCost()).isNull();
        assertThat(task.getHumanReviewRequired()).isFalse();
        assertThat(task.getHumanReviewStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(task.getHumanReviewNote()).isNull();
        assertThat(task.getHumanReviewBy()).isNull();
        assertThat(task.getHumanReviewedAt()).isNull();
        assertThat(task.getStartedAt()).isNull();
        assertThat(task.getFinishedAt()).isNull();
        assertThat(task.getDurationSeconds()).isZero();
    }
}
