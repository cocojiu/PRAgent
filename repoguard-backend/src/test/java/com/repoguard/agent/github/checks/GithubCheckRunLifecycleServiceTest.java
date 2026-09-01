package com.repoguard.agent.github.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.GithubCheckRun;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.GithubCheckRunMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class GithubCheckRunLifecycleServiceTest {

    private final GithubCheckRunMapper mapper = mock(GithubCheckRunMapper.class);
    private final GithubCheckRunProperties properties = enabledProperties();
    private final GithubCheckRunLifecycleService service = new GithubCheckRunLifecycleService(mapper, properties);

    @Test
    void createsFirstRunWithStableExternalIdAndAdvancesOrderedStages() {
        ReviewTask task = task();
        GithubCheckRun first = null;
        when(mapper.selectLatestForTask(7L)).thenReturn(null, first = record("QUEUED", 1), record("IN_PROGRESS", 1));

        service.queued(task);
        service.inProgress(task);
        service.completed(task);

        ArgumentCaptor<GithubCheckRun> inserted = ArgumentCaptor.forClass(GithubCheckRun.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getRunSequence()).isEqualTo(1);
        assertThat(inserted.getValue().getExternalId()).isEqualTo("repoguard-task:7:run:1");
        verify(mapper, org.mockito.Mockito.times(2)).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disabledGateDoesNotTouchPersistence() {
        properties.setEnabled(false);
        service.queued(task());

        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    @Test
    void startsANewSequenceAfterACompletedRun() {
        GithubCheckRun completed = record("COMPLETED", 4);
        completed.setRunSequence(4);
        when(mapper.selectLatestForTask(7L)).thenReturn(completed);

        service.queued(task());

        ArgumentCaptor<GithubCheckRun> inserted = ArgumentCaptor.forClass(GithubCheckRun.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getRunSequence()).isEqualTo(5);
        assertThat(inserted.getValue().getDesiredStage()).isEqualTo("QUEUED");
    }

    @Test
    void rejectsMissingCommitShaAndFailedSequenceCreation() {
        ReviewTask missingSha = task();
        missingSha.setCommitSha(" ");
        assertThatThrownBy(() -> service.queued(missingSha))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("commit SHA");

        when(mapper.selectLatestForTask(7L)).thenReturn((GithubCheckRun) null, (GithubCheckRun) null);
        assertThatThrownBy(() -> service.inProgress(task()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("could not be created");
    }

    @Test
    void handlesDuplicateInsertWhenAnotherWorkerAlreadyCreatedTheRun() {
        GithubCheckRun existing = record("QUEUED", 1);
        when(mapper.selectLatestForTask(7L)).thenReturn(null, existing);
        when(mapper.insert(org.mockito.ArgumentMatchers.<GithubCheckRun>any()))
            .thenThrow(new DuplicateKeyException("race"));

        service.queued(task());

        when(mapper.selectLatestForTask(7L)).thenReturn((GithubCheckRun) null, (GithubCheckRun) null);
        assertThatThrownBy(() -> service.queued(task()))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void doesNotMoveACompletedStageBackwardsAndRetriesTerminalCompletion() {
        GithubCheckRun inProgress = record("IN_PROGRESS", 2);
        when(mapper.selectLatestForTask(7L)).thenReturn(inProgress);
        service.queued(task());
        service.inProgress(task());

        GithubCheckRun completed = record("COMPLETED", 3);
        when(mapper.selectLatestForTask(7L)).thenReturn(completed);
        service.completed(task());

        verify(mapper, org.mockito.Mockito.times(1)).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void parsesStageAliasesAndRejectsUnknownValues() {
        assertThat(GithubCheckRunStage.from(" in-progress ")).isEqualTo(GithubCheckRunStage.IN_PROGRESS);
        assertThat(GithubCheckRunStage.from("queued")).isEqualTo(GithubCheckRunStage.QUEUED);
        assertThat(GithubCheckRunStage.from(" ")).isNull();
        assertThatThrownBy(() -> GithubCheckRunStage.from("running"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown GitHub Check Run stage");
    }

    private GithubCheckRunProperties enabledProperties() {
        GithubCheckRunProperties value = new GithubCheckRunProperties();
        value.setEnabled(true);
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

    private GithubCheckRun record(String stage, long version) {
        GithubCheckRun value = new GithubCheckRun();
        value.setId(10L);
        value.setTaskId(7L);
        value.setRunSequence(1);
        value.setDesiredStage(stage);
        value.setDesiredVersion(version);
        return value;
    }
}
