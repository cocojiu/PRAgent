package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final GithubCommentPublicationMapper githubCommentPublicationMapper = org.mockito.Mockito.mock(GithubCommentPublicationMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTaskPublisher reviewTaskPublisher = org.mockito.Mockito.mock(ReviewTaskPublisher.class);
    private final GithubPullRequestClient githubPullRequestClient = org.mockito.Mockito.mock(GithubPullRequestClient.class);
    private final ReviewServiceImpl service = new ReviewServiceImpl(
        reviewTaskMapper,
        changedFileMapper,
        reviewFindingMapper,
        githubCommentPublicationMapper,
        reviewTimelineMapper,
        reviewTaskPublisher,
        githubPullRequestClient
    );

    @Test
    void getGithubCommentPreviewBuildsCommentDraftsAndBlocksMissingLine() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "命令与描述未正确分隔", "添加空格或换行"),
            finding(2L, "LOW", "README", 3, "文档可读性不足", "补充分隔符"),
            finding(3L, "LOW", "README", 4, "可能导致误解", "调整格式"),
            finding(4L, "LOW", "README", null, "文件末尾缺少换行符", "添加换行符")
        ));

        var preview = service.getGithubCommentPreview(521L);

        assertThat(preview.totalFindings()).isEqualTo(4);
        assertThat(preview.commentableCount()).isEqualTo(4);
        assertThat(preview.blockedCount()).isZero();
        assertThat(preview.items().getFirst().commentBody())
            .contains("**RepoGuard LOW finding**")
            .contains("命令与描述未正确分隔")
            .contains("**建议**：添加空格或换行");
        assertThat(preview.items().getLast().commentable()).isTrue();
        assertThat(preview.items().getLast().targetType()).isEqualTo("pull_request");
        assertThat(preview.items().getLast().reason()).isEqualTo("Finding is missing a valid line number and will be posted as a PR comment");
    }

    @Test
    void publishGithubCommentsSendsOnlyCommentableDrafts() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(githubCommentPublicationMapper.selectOne(any())).thenReturn(null);
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "命令与描述未正确分隔", "添加空格或换行"),
            finding(2L, "LOW", "README", null, "文件末尾缺少换行符", "添加换行符")
        ));
        when(githubPullRequestClient.publishPullRequestComments(any(), any())).thenReturn(List.of(
            new GithubReviewCommentResult(1L, "README", 2, "line", true, "published", "GitHub comment published", "https://github.com/comment/1", 101L),
            new GithubReviewCommentResult(2L, "README", null, "pull_request", true, "published", "GitHub comment published", "https://github.com/comment/2", 102L)
        ));

        var result = service.publishGithubComments(521L);

        assertThat(result.totalFindings()).isEqualTo(2);
        assertThat(result.attemptedCount()).isEqualTo(2);
        assertThat(result.succeededCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(result.skippedCount()).isZero();
        assertThat(result.items()).extracting("status").containsExactly("published", "published");
        verify(githubCommentPublicationMapper, org.mockito.Mockito.times(2)).insert(any(GithubCommentPublication.class));
    }

    @Test
    void publishGithubCommentsSkipsAlreadyPublishedFindings() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "Use logger", "Replace stdout with logger")
        ));
        GithubCommentPublication publication = new GithubCommentPublication();
        publication.setTaskId(521L);
        publication.setFindingId(1L);
        publication.setTargetType("line");
        publication.setSuccess(true);
        publication.setStatus("published");
        publication.setGithubUrl("https://github.com/comment/1");
        publication.setMessage("GitHub comment published");
        publication.setPublishedAt(LocalDateTime.of(2026, 6, 7, 10, 0));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of(publication));

        var result = service.publishGithubComments(521L);

        assertThat(result.attemptedCount()).isZero();
        assertThat(result.succeededCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.items()).extracting("status").containsExactly("already_published");
        assertThat(result.items().getFirst().url()).isEqualTo("https://github.com/comment/1");
        verify(githubPullRequestClient, never()).publishPullRequestComments(any(), any());
    }

    @Test
    void triggerManualReviewReturnsExistingTaskWithoutPublishingDuplicateMessage() {
        when(reviewTaskMapper.selectOne(any())).thenReturn(task());

        var result = service.triggerManualReview(new ManualReviewRequest(
            "octocat",
            "Hello-World",
            1,
            "Smoke review",
            "public-pr-1-llm-string-response",
            "master"
        ));

        assertThat(result.taskId()).isEqualTo(521L);
        assertThat(result.existing()).isTrue();
        assertThat(result.message()).isEqualTo("Review task already exists");
        verify(reviewTaskMapper, never()).insert(any(ReviewTask.class));
        verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(521L);
        task.setPrNumber(1);
        task.setTitle("Smoke review");
        task.setRepository("Hello-World");
        task.setOrganization("octocat");
        task.setCommitSha("public-pr-1-llm-string-response");
        task.setBranchName("master");
        task.setStatus("COMPLETED");
        task.setRiskLevel("LOW");
        task.setMqRetries(0);
        task.setLlmStatus("COMPLETED");
        task.setPrUrl("https://github.com/octocat/Hello-World/pull/1");
        task.setCreatedAt(LocalDateTime.now());
        task.setDurationSeconds(37);
        return task;
    }

    private ChangedFile changedFile(String path, String changeType) {
        ChangedFile file = new ChangedFile();
        file.setTaskId(521L);
        file.setFilePath(path);
        file.setChangeType(changeType);
        file.setAdditions(6);
        file.setDeletions(1);
        return file;
    }

    private ReviewFinding finding(Long id, String severity, String file, Integer line, String message, String recommendation) {
        ReviewFinding finding = new ReviewFinding();
        finding.setId(id);
        finding.setTaskId(521L);
        finding.setCategory("FINDING");
        finding.setSeverity(severity);
        finding.setSource("LLM");
        finding.setRuleId("LLM");
        finding.setFilePath(file);
        finding.setLineNumber(line);
        finding.setMessage(message);
        finding.setRecommendation(recommendation);
        return finding;
    }
}
