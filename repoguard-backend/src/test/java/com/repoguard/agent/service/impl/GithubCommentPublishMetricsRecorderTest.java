package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class GithubCommentPublishMetricsRecorderTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final GithubCommentPublishMetricsRecorder recorder = new GithubCommentPublishMetricsRecorder(metrics);

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new GithubCommentPublishMetricsRecorder(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void recordsItemOutcomeCountersByStatus() {
        recorder.recordItems(2, 1, 1);

        verify(metrics, org.mockito.Mockito.times(2)).githubCommentPublished("success");
        verify(metrics).githubCommentPublished("failed");
        verify(metrics).githubCommentPublished("skipped");
    }

    @Test
    void ignoresNegativeItemCounts() {
        recorder.recordItems(-1, -2, -3);

        verify(metrics, never()).githubCommentPublished(any());
    }

    @Test
    void recordsDurationWithStableResultTags() {
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(1);

        recorder.recordDuration(startedAt, false);
        recorder.recordDuration(startedAt, true);

        verify(metrics).githubCommentPublishDuration(any(Duration.class), org.mockito.Mockito.eq("success"));
        verify(metrics).githubCommentPublishDuration(any(Duration.class), org.mockito.Mockito.eq("failed"));
    }
}
