package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewResult;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewTaskExecutorImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final GithubPullRequestClient githubPullRequestClient = org.mockito.Mockito.mock(GithubPullRequestClient.class);
    private final PullRequestReviewer pullRequestReviewer = org.mockito.Mockito.mock(PullRequestReviewer.class);
    private final ReviewTaskExecutorImpl executor = new ReviewTaskExecutorImpl(
        reviewTaskMapper,
        reviewTimelineMapper,
        reviewFindingMapper,
        changedFileMapper,
        githubPullRequestClient,
        pullRequestReviewer
    );

    @Test
    void executeMovesQueuedTaskToCompletedAndWritesTimeline() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("QUEUED");
        task.setRiskLevel("INFO");
        task.setLlmStatus("PENDING");
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1);
        GithubPullRequestDiff diff = new GithubPullRequestDiff(
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            List.of(new GithubChangedFile("src/App.java", "modified", 3, 1, "+System.out.println(\"debug\");"))
        );
        when(githubPullRequestClient.fetchPullRequestDiff(task)).thenReturn(diff);
        when(pullRequestReviewer.review(task, diff)).thenReturn(ReviewResult.completed(
            "LOW",
            List.of(new ReviewFindingResult("LOW", "RULE", "RG-JAVA-002", "src/App.java", 10, "标准输出日志", "改用日志组件"))
        ));

        executor.execute(message());

        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        assertThat(task.getLlmStatus()).isEqualTo("COMPLETED");
        assertThat(task.getRiskLevel()).isEqualTo("LOW");
        assertThat(task.getStartedAt()).isNotNull();
        assertThat(task.getFinishedAt()).isNotNull();
        assertThat(task.getDurationSeconds()).isNotNull();
        verify(reviewTaskMapper).update(any(UpdateWrapper.class));
        verify(reviewTaskMapper).updateById(task);
        verify(changedFileMapper).insert(any(ChangedFile.class));
        verify(reviewFindingMapper).insert(any(ReviewFinding.class));
        verify(reviewTimelineMapper, org.mockito.Mockito.times(4)).insert(any(ReviewTimeline.class));
    }

    @Test
    void executeMovesMediumRiskTaskToPendingHumanReview() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("QUEUED");
        task.setRiskLevel("INFO");
        task.setLlmStatus("PENDING");
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1);
        GithubPullRequestDiff diff = new GithubPullRequestDiff(
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            List.of(new GithubChangedFile("src/App.java", "modified", 3, 1, "+Thread.sleep(1000);"))
        );
        when(githubPullRequestClient.fetchPullRequestDiff(task)).thenReturn(diff);
        when(pullRequestReviewer.review(task, diff)).thenReturn(ReviewResult.completed(
            "MEDIUM",
            List.of(new ReviewFindingResult("MEDIUM", "RULE", "RG-JAVA-003", "src/App.java", 10, "固定休眠", "改用可测试等待"))
        ));

        executor.execute(message());

        assertThat(task.getStatus()).isEqualTo("PENDING_HUMAN_REVIEW");
        assertThat(task.getHumanReviewRequired()).isTrue();
        assertThat(task.getHumanReviewStatus()).isEqualTo("PENDING");
        verify(reviewTaskMapper).updateById(task);
    }

    @Test
    void executeDeduplicatesFindingsBeforePersisting() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("QUEUED");
        task.setRiskLevel("INFO");
        task.setLlmStatus("PENDING");
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1);
        GithubPullRequestDiff diff = new GithubPullRequestDiff(
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            List.of(new GithubChangedFile("src/App.java", "modified", 3, 1, "+System.out.println(\"debug\");"))
        );
        when(githubPullRequestClient.fetchPullRequestDiff(task)).thenReturn(diff);
        when(pullRequestReviewer.review(task, diff)).thenReturn(ReviewResult.completed(
            "HIGH",
            List.of(
                new ReviewFindingResult("LOW", "LLM", "LLM", "src/App.java", 10, "Use logger", "Replace stdout"),
                new ReviewFindingResult("HIGH", "RULE", "RG-JAVA-002", "src/App.java", 10, "Use logger", "Use structured logger")
            )
        ));

        executor.execute(message());

        ArgumentCaptor<ReviewFinding> findingCaptor = ArgumentCaptor.forClass(ReviewFinding.class);
        verify(reviewFindingMapper).insert(findingCaptor.capture());
        ReviewFinding finding = findingCaptor.getValue();
        assertThat(finding.getSeverity()).isEqualTo("HIGH");
        assertThat(finding.getSource()).isEqualTo("LLM / RULE");
        assertThat(finding.getRuleId()).isEqualTo("LLM / RG-JAVA-002");
        assertThat(finding.getRecommendation()).isEqualTo("Replace stdout / Use structured logger");
    }

    @Test
    void executeStoresLlmQualityMetadata() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("QUEUED");
        task.setRiskLevel("INFO");
        task.setLlmStatus("PENDING");
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1);
        GithubPullRequestDiff diff = new GithubPullRequestDiff(
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            List.of(new GithubChangedFile("src/App.java", "modified", 3, 1, "+logger.info(\"ok\");"))
        );
        when(githubPullRequestClient.fetchPullRequestDiff(task)).thenReturn(diff);
        when(pullRequestReviewer.review(task, diff)).thenReturn(ReviewResult.completed(
            "LOW",
            List.of(),
            "openai",
            "gpt-test",
            1234,
            "parsed",
            "PR repo-guard-demo/spring-boot-demo#512; files=1"
        ));

        executor.execute(message());

        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        assertThat(task.getLlmProvider()).isEqualTo("openai");
        assertThat(task.getLlmModel()).isEqualTo("gpt-test");
        assertThat(task.getLlmDurationMs()).isEqualTo(1234);
        assertThat(task.getLlmParseStatus()).isEqualTo("parsed");
        assertThat(task.getLlmPromptSummary()).contains("files=1");
        verify(reviewTaskMapper).updateById(task);
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

    @Test
    void executeIgnoresTaskAlreadyInProgress() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("REVIEWING");
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);

        executor.execute(message());

        verify(reviewTaskMapper, never()).update(any(UpdateWrapper.class));
        verify(reviewTaskMapper, never()).updateById(any(ReviewTask.class));
        verify(githubPullRequestClient, never()).fetchPullRequestDiff(any(ReviewTask.class));
        verify(reviewTimelineMapper, never()).insert(any(ReviewTimeline.class));
    }

    @Test
    void executeStopsWhenQueuedTaskWasClaimedByAnotherConsumer() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("QUEUED");
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(0);

        executor.execute(message());

        verify(reviewTaskMapper).update(any(UpdateWrapper.class));
        verify(reviewTaskMapper, never()).updateById(any(ReviewTask.class));
        verify(githubPullRequestClient, never()).fetchPullRequestDiff(any(ReviewTask.class));
        verify(reviewTimelineMapper, never()).insert(any(ReviewTimeline.class));
    }

    @Test
    void executeMarksTaskFailedWhenDiffFetchFails() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("QUEUED");
        task.setRiskLevel("INFO");
        task.setLlmStatus("PENDING");
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1);
        when(githubPullRequestClient.fetchPullRequestDiff(task)).thenThrow(new IllegalStateException("github unavailable"));

        executor.execute(message());

        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getLlmStatus()).isEqualTo("FAILED");
        assertThat(task.getRiskLevel()).isEqualTo("HIGH");
        verify(reviewTaskMapper).update(any(UpdateWrapper.class));
        verify(reviewTaskMapper).updateById(task);
        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper, org.mockito.Mockito.times(2)).insert(timelineCaptor.capture());
        assertThat(timelineCaptor.getAllValues().get(1).getLabel()).contains("github unavailable");
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
