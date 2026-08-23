package com.repoguard.agent.review.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.LlmReviewVersions;
import com.repoguard.agent.review.ReviewExecutionProvenance;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ServerRiskAggregator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewExecutionAttemptLifecycleTest {

    private final ReviewExecutionAttemptMapper attemptMapper = org.mockito.Mockito.mock(
        ReviewExecutionAttemptMapper.class
    );
    private final ReviewTaskMapper taskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewFindingMapper findingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final ReviewExecutionAttemptLifecycle lifecycle = new ReviewExecutionAttemptLifecycle(
        attemptMapper,
        taskMapper,
        findingMapper,
        changedFileMapper,
        metrics
    );

    @Test
    void startsAttemptAndRotatesCurrentArtifacts() {
        ReviewTask task = task();
        LocalDateTime startedAt = LocalDateTime.parse("2026-08-23T12:30:00");
        when(attemptMapper.selectLatestAttemptNo(42L)).thenReturn(2);
        when(attemptMapper.insert(any(ReviewExecutionAttempt.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ReviewExecutionAttempt.class).setId(99L);
            return 1;
        });
        when(taskMapper.attachCurrentAttempt(42L, "claim-7", 99L)).thenReturn(1);

        ReviewExecutionAttempt attempt = lifecycle.start(task, "claim-7", "worker-a", startedAt);

        assertThat(attempt.getId()).isEqualTo(99L);
        assertThat(attempt.getTaskId()).isEqualTo(42L);
        assertThat(attempt.getAttemptNo()).isEqualTo(3);
        assertThat(attempt.getGeneration()).isEqualTo(4L);
        assertThat(attempt.getCommitSha()).isEqualTo("abc123");
        assertThat(attempt.getInputFingerprint()).hasSize(64);
        assertThat(attempt.getClaimId()).isEqualTo("claim-7");
        assertThat(attempt.getWorkerId()).isEqualTo("worker-a");
        assertThat(attempt.getStatus()).isEqualTo(ReviewExecutionAttemptLifecycle.RUNNING);
        assertThat(attempt.getQueuedAt()).isEqualTo(task.getCreatedAt());
        assertThat(task.getCurrentAttemptId()).isEqualTo(99L);
        verify(findingMapper).markCurrentAttemptHistorical(42L);
        verify(changedFileMapper).markCurrentAttemptHistorical(42L);
        verify(metrics).reviewExecutionAttempt(ReviewExecutionAttemptLifecycle.RUNNING);
    }

    @Test
    void usesStableDefaultsWhenOptionalTaskIdentityFieldsAreMissing() {
        ReviewTask task = new ReviewTask();
        task.setId(7L);
        when(attemptMapper.insert(any(ReviewExecutionAttempt.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ReviewExecutionAttempt.class).setId(8L);
            return 1;
        });
        when(taskMapper.attachCurrentAttempt(7L, null, 8L)).thenReturn(1);
        ReviewExecutionAttemptLifecycle withoutMetrics = new ReviewExecutionAttemptLifecycle(
            attemptMapper,
            taskMapper,
            findingMapper,
            changedFileMapper
        );
        LocalDateTime startedAt = LocalDateTime.parse("2026-08-23T12:30:00");

        ReviewExecutionAttempt attempt = withoutMetrics.start(task, null, null, startedAt);

        assertThat(attempt.getGeneration()).isEqualTo(1L);
        assertThat(attempt.getQueuedAt()).isEqualTo(startedAt);
        assertThat(attempt.getInputFingerprint()).hasSize(64);
        verify(metrics, never()).reviewExecutionAttempt(any());
    }

    @Test
    void rejectsAttemptCreationAndClaimLoss() {
        ReviewTask task = task();
        when(attemptMapper.insert(any(ReviewExecutionAttempt.class))).thenReturn(0);

        assertThatThrownBy(() -> lifecycle.start(task, "claim-7", "worker-a", LocalDateTime.now()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Review execution Attempt could not be created");

        when(attemptMapper.insert(any(ReviewExecutionAttempt.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ReviewExecutionAttempt.class).setId(100L);
            return 1;
        });
        when(taskMapper.attachCurrentAttempt(42L, "claim-8", 100L)).thenReturn(0);

        assertThatThrownBy(() -> lifecycle.start(task, "claim-8", "worker-a", LocalDateTime.now()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Review execution Attempt lost its task claim");
    }

    @Test
    void completesAttemptWithStageDurationsTokensAndProvenance() {
        ReviewExecutionAttempt attempt = runningAttempt(99L);
        ReviewAttemptStageDurations stages = stages();
        LocalDateTime finishedAt = attempt.getStartedAt().plusSeconds(2);
        ReviewExecutionProvenance provenance = new ReviewExecutionProvenance(
            7L,
            LlmReviewVersions.PROMPT,
            LlmReviewVersions.CONTEXT,
            LlmReviewVersions.SCHEMA,
            LlmReviewVersions.VERIFIER,
            ServerRiskAggregator.VERSION
        );
        ReviewResult result = ReviewResult.completed(
            "HIGH",
            List.of(),
            "openai",
            "gpt",
            120,
            "VALID",
            "prompt",
            11,
            12,
            23,
            new BigDecimal("0.25"),
            provenance
        );
        when(attemptMapper.update(any())).thenReturn(1);

        lifecycle.complete(
            attempt,
            result,
            stages,
            finishedAt,
            ReviewExecutionAttemptLifecycle.PARTIAL,
            "BUDGET_EXHAUSTED",
            "llm"
        );

        assertThat(attempt.getStatus()).isEqualTo(ReviewExecutionAttemptLifecycle.PARTIAL);
        assertThat(attempt.getFinishedAt()).isEqualTo(finishedAt);
        verify(attemptMapper).update(any());
        verify(metrics).reviewExecutionAttempt(ReviewExecutionAttemptLifecycle.PARTIAL);
    }

    @Test
    void failsSupersedesAndRejectsLostTerminalOwnership() {
        when(attemptMapper.update(any())).thenReturn(1, 1, 0);
        ReviewExecutionAttempt failed = runningAttempt(1L);
        ReviewExecutionAttempt superseded = runningAttempt(2L);

        lifecycle.fail(failed, null, failed.getStartedAt().minusSeconds(1), "NETWORK");
        lifecycle.supersede(superseded, stages(), superseded.getStartedAt().plusSeconds(1));

        assertThat(failed.getStatus()).isEqualTo(ReviewExecutionAttemptLifecycle.FAILED);
        assertThat(superseded.getStatus()).isEqualTo(ReviewExecutionAttemptLifecycle.SUPERSEDED);
        assertThatThrownBy(() -> lifecycle.fail(
            runningAttempt(3L),
            stages(),
            LocalDateTime.parse("2026-08-23T12:30:01"),
            "DATABASE"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Review execution Attempt terminal update lost ownership");
        verify(metrics).reviewExecutionAttempt(ReviewExecutionAttemptLifecycle.FAILED);
        verify(metrics).reviewExecutionAttempt(ReviewExecutionAttemptLifecycle.SUPERSEDED);
    }

    @Test
    void refreshesDurationsOnlyForPersistedAttemptsWithStages() {
        ReviewExecutionAttempt attempt = runningAttempt(5L);
        lifecycle.refreshDurations(null, stages());
        lifecycle.refreshDurations(new ReviewExecutionAttempt(), stages());
        lifecycle.refreshDurations(attempt, null);
        lifecycle.refreshDurations(attempt, stages());

        verify(attemptMapper, times(1)).update(any());
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setOrganization(" octocat ");
        task.setRepository(" Hello-World ");
        task.setPrNumber(17);
        task.setCommitSha("abc123");
        task.setGeneration(4L);
        task.setCreatedAt(LocalDateTime.parse("2026-08-23T12:00:00"));
        return task;
    }

    private ReviewExecutionAttempt runningAttempt(long id) {
        ReviewExecutionAttempt attempt = new ReviewExecutionAttempt();
        attempt.setId(id);
        attempt.setStatus(ReviewExecutionAttemptLifecycle.RUNNING);
        attempt.setStartedAt(LocalDateTime.parse("2026-08-23T12:30:00"));
        return attempt;
    }

    private ReviewAttemptStageDurations stages() {
        ReviewAttemptStageDurations stages = new ReviewAttemptStageDurations();
        stages.add("diff_fetch", Duration.ofMillis(10));
        stages.add("review", Duration.ofMillis(20));
        stages.add("db_write", Duration.ofMillis(30));
        return stages;
    }
}
