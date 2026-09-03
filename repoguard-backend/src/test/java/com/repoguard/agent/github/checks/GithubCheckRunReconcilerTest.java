package com.repoguard.agent.github.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.GithubCheckRun;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.GithubCheckRunMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubCheckRunReconcilerTest {

    private final GithubCheckRunMapper checkRunMapper = mock(GithubCheckRunMapper.class);
    private final ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
    private final GithubCheckRunClient client = mock(GithubCheckRunClient.class);
    private final GithubCheckRunOutcomeResolver outcomeResolver = mock(GithubCheckRunOutcomeResolver.class);
    private final GithubCheckRunProperties properties = enabledProperties();
    private final GithubCheckRunReconciler reconciler = new GithubCheckRunReconciler(
        checkRunMapper, taskMapper, client, outcomeResolver, properties
    );

    @Test
    void reconcilesQueuedRunThroughInProgressAndCompletedStates() {
        ReviewTask task = task();
        GithubCheckRun queued = record("COMPLETED", null, 2L, 0L);
        GithubCheckRun afterCreate = record("COMPLETED", "QUEUED", 2L, 1L);
        afterCreate.setGithubCheckRunId(99L);
        GithubCheckRun afterProgress = record("COMPLETED", "IN_PROGRESS", 2L, 2L);
        afterProgress.setGithubCheckRunId(99L);
        GithubCheckRunGateway.RemoteCheckRun remote = new GithubCheckRunGateway.RemoteCheckRun(
            99L, queued.getExternalId(), "queued", null
        );
        GithubCheckRunOutcomeResolver.Outcome outcome = new GithubCheckRunOutcomeResolver.Outcome(
            "failure", "发现阻断问题", 1, List.of()
        );
        when(checkRunMapper.selectById(8L)).thenReturn(queued, afterCreate, afterProgress);
        when(checkRunMapper.selectDue(any(), any(), anyInt())).thenReturn(List.of(queued));
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(checkRunMapper.claim(eq(8L), any(), any(), anyString())).thenReturn(1);
        when(client.findOrCreate(eq(task), eq(queued), any())).thenReturn(remote);
        when(client.update(eq(task), any(), any())).thenReturn(remote);
        when(outcomeResolver.resolve(task)).thenReturn(outcome);
        when(checkRunMapper.markCreated(anyLong(), anyString(), anyLong(), anyString(), any())).thenReturn(1);
        when(checkRunMapper.markApplied(anyLong(), anyString(), anyString(), anyLong(), any())).thenReturn(1);
        when(checkRunMapper.release(anyLong(), anyString(), any())).thenReturn(1);

        reconciler.reconcileDue();

        verify(client).findOrCreate(eq(task), eq(queued), any());
        verify(client, org.mockito.Mockito.atLeast(2)).update(eq(task), any(), any());
        verify(outcomeResolver).resolve(task);
        verify(checkRunMapper).release(eq(8L), anyString(), any());
    }

    @Test
    void recordsRetryAfterExternalFailureAndReleasesClaim() {
        ReviewTask task = task();
        GithubCheckRun record = record("QUEUED", null, 1L, 0L);
        when(checkRunMapper.selectById(8L)).thenReturn(record);
        when(checkRunMapper.selectDue(any(), any(), anyInt())).thenReturn(List.of(record));
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(checkRunMapper.claim(eq(8L), any(), any(), anyString())).thenReturn(1);
        when(client.findOrCreate(eq(task), eq(record), any()))
            .thenThrow(new IllegalStateException("GitHub unavailable"));
        when(checkRunMapper.markFailed(anyLong(), anyString(), any(), anyString(), any())).thenReturn(1);

        reconciler.reconcileDue();

        verify(checkRunMapper).markFailed(eq(8L), anyString(), any(), eq("GitHub unavailable"), any());
    }

    @Test
    void skipsWhenDisabledOrTaskWasDeleted() {
        properties.setEnabled(false);
        reconciler.reconcileDue();
        verifyNoMapperInteractions();

        properties.setEnabled(true);
        GithubCheckRun record = record("QUEUED", null, 1L, 0L);
        when(checkRunMapper.selectDue(any(), any(), anyInt())).thenReturn(List.of(record));
        when(checkRunMapper.claim(anyLong(), any(), any(), anyString())).thenReturn(1);
        when(checkRunMapper.selectById(8L)).thenReturn(record);
        when(taskMapper.selectById(7L)).thenReturn(null);

        reconciler.reconcileDue();

        verify(checkRunMapper).release(eq(8L), anyString(), any());
        verify(client, never()).findOrCreate(any(), any(), any());
    }

    @Test
    void skipsNullDueRowsAndRowsThatAnotherWorkerCouldNotClaim() {
        GithubCheckRun record = record("QUEUED", null, 1L, 0L);
        when(checkRunMapper.selectDue(any(), any(), anyInt())).thenReturn(java.util.Arrays.asList(null, record));
        when(checkRunMapper.claim(eq(8L), any(), any(), anyString())).thenReturn(0);

        reconciler.reconcileDue();

        verify(checkRunMapper).selectDue(any(), any(), anyInt());
        verify(checkRunMapper).claim(eq(8L), any(), any(), anyString());
        verify(taskMapper, never()).selectById(any());
    }

    @Test
    void marksLongFailureWithBackoffWhenDispatchAttemptsAreMissing() {
        ReviewTask task = task();
        GithubCheckRun record = record("QUEUED", null, 1L, 0L);
        record.setDispatchAttempts(null);
        String longMessage = "x".repeat(950);
        when(checkRunMapper.selectById(8L)).thenReturn(record, record);
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(client.findOrCreate(eq(task), eq(record), any())).thenThrow(new IllegalStateException(longMessage));
        when(checkRunMapper.markFailed(anyLong(), anyString(), any(), anyString(), any())).thenReturn(1);

        reconciler.reconcileClaimed(8L, "claim-8");

        org.mockito.ArgumentCaptor<String> message = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(checkRunMapper).markFailed(eq(8L), eq("claim-8"), any(), message.capture(), any());
        assertThat(message.getValue()).hasSize(900).endsWith("...");
    }

    @Test
    void publishesCompletedAnnotationsInGithubBatchSizes() {
        properties.setAnnotationLimit(1);
        ReviewTask task = task();
        GithubCheckRun record = record("COMPLETED", "IN_PROGRESS", 3L, 2L);
        record.setGithubCheckRunId(99L);
        GithubCheckRunGateway.Annotation first = new GithubCheckRunGateway.Annotation(
            "src/One.java", 1, 1, "failure", "one", "RG-1", null
        );
        GithubCheckRunGateway.Annotation second = new GithubCheckRunGateway.Annotation(
            "src/Two.java", 2, 2, "failure", "two", "RG-2", null
        );
        when(checkRunMapper.selectById(8L)).thenReturn(record);
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(outcomeResolver.resolve(task)).thenReturn(new GithubCheckRunOutcomeResolver.Outcome(
            "failure", "两个问题", 2, List.of(first, second)
        ));
        when(client.update(eq(task), eq(record), any())).thenReturn(
            new GithubCheckRunGateway.RemoteCheckRun(99L, record.getExternalId(), "completed", "failure")
        );

        reconciler.reconcileClaimed(8L, "claim-8");

        verify(client, org.mockito.Mockito.times(2)).update(eq(task), eq(record), any());
        verify(checkRunMapper).markApplied(eq(8L), eq("claim-8"), eq("COMPLETED"), eq(3L), any());
        verify(checkRunMapper).release(eq(8L), eq("claim-8"), any());
    }

    @Test
    void mapsRemoteProgressStatesAndMissingRecordsSafely() {
        ReviewTask task = task();
        GithubCheckRun queued = record("IN_PROGRESS", null, 2L, 0L);
        GithubCheckRun afterCreate = record("IN_PROGRESS", "IN_PROGRESS", 2L, 2L);
        afterCreate.setGithubCheckRunId(101L);
        when(checkRunMapper.selectById(8L)).thenReturn(queued, afterCreate);
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(client.findOrCreate(eq(task), eq(queued), any())).thenReturn(
            new GithubCheckRunGateway.RemoteCheckRun(101L, queued.getExternalId(), "in_progress", null)
        );

        reconciler.reconcileClaimed(8L, "claim-8");
        verify(checkRunMapper).markCreated(eq(8L), eq("claim-8"), eq(101L), eq("IN_PROGRESS"), any());

        reconciler.reconcileClaimed(99L, "missing");
        verify(checkRunMapper, never()).release(eq(99L), anyString(), any());
    }

    private void verifyNoMapperInteractions() {
        verify(checkRunMapper, never()).selectDue(any(), any(), anyInt());
        verify(checkRunMapper, never()).selectById(anyLong());
    }

    private GithubCheckRunProperties enabledProperties() {
        GithubCheckRunProperties value = new GithubCheckRunProperties();
        value.setEnabled(true);
        value.setRetryBaseSeconds(1);
        value.setRetryMaxSeconds(30);
        return value;
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(7L);
        task.setOrganization("octo");
        task.setRepository("repo");
        task.setCommitSha("0123456789abcdef0123456789abcdef01234567");
        return task;
    }

    private GithubCheckRun record(String desired, String applied, long desiredVersion, long appliedVersion) {
        GithubCheckRun value = new GithubCheckRun();
        value.setId(8L);
        value.setTaskId(7L);
        value.setRunSequence(1);
        value.setName("RepoGuard PR Review");
        value.setHeadSha("0123456789abcdef0123456789abcdef01234567");
        value.setExternalId("repoguard-task:7:run:1");
        value.setDesiredStage(desired);
        value.setAppliedStage(applied);
        value.setDesiredVersion(desiredVersion);
        value.setAppliedVersion(appliedVersion);
        value.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return value;
    }
}
