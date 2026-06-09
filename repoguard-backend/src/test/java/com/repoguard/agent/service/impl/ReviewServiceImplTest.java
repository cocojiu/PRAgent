package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final GithubCommentPublicationMapper githubCommentPublicationMapper = org.mockito.Mockito.mock(GithubCommentPublicationMapper.class);
    private final GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper = org.mockito.Mockito.mock(GithubCommentPublicationBatchMapper.class);
    private final GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper = org.mockito.Mockito.mock(GithubCommentPublicationBatchItemMapper.class);
    private final IntegrationConfigMapper integrationConfigMapper = org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTaskPublisher reviewTaskPublisher = org.mockito.Mockito.mock(ReviewTaskPublisher.class);
    private final GithubPullRequestClient githubPullRequestClient = org.mockito.Mockito.mock(GithubPullRequestClient.class);
    private final ReviewServiceImpl service = new ReviewServiceImpl(
        reviewTaskMapper,
        changedFileMapper,
        reviewFindingMapper,
        githubCommentPublicationMapper,
        githubCommentPublicationBatchMapper,
        githubCommentPublicationBatchItemMapper,
        integrationConfigMapper,
        reviewTimelineMapper,
        reviewTaskPublisher,
        githubPullRequestClient
    );

    @Test
    void getGithubCommentPreviewBuildsCommentDraftsAndBlocksMissingLine() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(integrationConfigMapper.selectOne(any())).thenReturn(githubConfig("octocat", "Hello-World", "CONFIGURED", "enc:v1:test", null));
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
        assertThat(preview.writebackCheck().status()).isEqualTo("ready");
        assertThat(preview.writebackCheck().repositoryMatched()).isTrue();
        assertThat(preview.items().getFirst().commentBody())
            .contains("**RepoGuard LOW finding**")
            .contains("命令与描述未正确分隔")
            .contains("**建议**：添加空格或换行");
        assertThat(preview.items().getLast().commentable()).isTrue();
        assertThat(preview.items().getLast().targetType()).isEqualTo("pull_request");
        assertThat(preview.items().getLast().reason()).isEqualTo("Finding is missing a valid line number and will be posted as a PR comment");
    }

    @Test
    void getGithubCommentPreviewWarnsWhenConfiguredRepositoryDiffersFromTaskRepository() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(integrationConfigMapper.selectOne(any())).thenReturn(githubConfig("other-owner", "other-repo", "CONFIGURED", "enc:v1:test", null));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "Use logger", "Replace stdout with logger")
        ));

        var preview = service.getGithubCommentPreview(521L);

        assertThat(preview.writebackCheck().status()).isEqualTo("repository_mismatch");
        assertThat(preview.writebackCheck().level()).isEqualTo("warning");
        assertThat(preview.writebackCheck().repositoryMatched()).isFalse();
        assertThat(preview.writebackCheck().messages())
            .contains("当前任务仓库与 GitHub 集成默认仓库不一致，请确认 Token 对目标仓库有评论权限。");
    }

    @Test
    void getGithubCommentPreviewWarnsWhenGithubTokenIsMissing() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(integrationConfigMapper.selectOne(any())).thenReturn(githubConfig("octocat", "Hello-World", "NOT_CONFIGURED", null, null));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "Use logger", "Replace stdout with logger")
        ));

        var preview = service.getGithubCommentPreview(521L);

        assertThat(preview.writebackCheck().status()).isEqualTo("token_missing");
        assertThat(preview.writebackCheck().level()).isEqualTo("danger");
        assertThat(preview.writebackCheck().tokenConfigured()).isFalse();
        assertThat(preview.writebackCheck().messages())
            .contains("GitHub Token 未配置，请先到集成配置页保存 Token。");
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
        verify(githubCommentPublicationBatchMapper).insert(any(GithubCommentPublicationBatch.class));
        verify(githubCommentPublicationBatchItemMapper, org.mockito.Mockito.times(2)).insert(any(GithubCommentPublicationBatchItem.class));
    }

    @Test
    void publishGithubCommentsAddsReadableFailureForPermissionError() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README", "MODIFY")));
        when(githubCommentPublicationMapper.selectList(any())).thenReturn(List.of());
        when(githubCommentPublicationMapper.selectOne(any())).thenReturn(null);
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README", 2, "Use logger", "Replace stdout with logger")
        ));
        when(githubPullRequestClient.publishPullRequestComments(any(), any())).thenReturn(List.of(
            new GithubReviewCommentResult(
                1L,
                "README",
                2,
                "line",
                false,
                "failed",
                "403 Resource not accessible by integration",
                null,
                null
            )
        ));

        var result = service.publishGithubComments(521L);

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.items().getFirst().failureCategory()).isEqualTo("github_permission_denied");
        assertThat(result.items().getFirst().failureReason()).isEqualTo("GitHub Token 权限不足");
        assertThat(result.items().getFirst().failureSuggestion()).contains("评论权限");
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
        verify(githubCommentPublicationBatchMapper).insert(any(GithubCommentPublicationBatch.class));
        verify(githubCommentPublicationBatchItemMapper).insert(any(GithubCommentPublicationBatchItem.class));
    }

    @Test
    void getGithubCommentPublicationHistoryReturnsBatchesAndItems() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        GithubCommentPublicationBatch batch = new GithubCommentPublicationBatch();
        batch.setId(10L);
        batch.setTaskId(521L);
        batch.setStatus("completed");
        batch.setTotalFindings(1);
        batch.setAttemptedCount(1);
        batch.setSucceededCount(1);
        batch.setFailedCount(0);
        batch.setSkippedCount(0);
        batch.setCreatedAt(LocalDateTime.of(2026, 6, 9, 12, 0));
        batch.setCompletedAt(LocalDateTime.of(2026, 6, 9, 12, 0, 1));
        GithubCommentPublicationBatchItem item = new GithubCommentPublicationBatchItem();
        item.setBatchId(10L);
        item.setTaskId(521L);
        item.setFindingId(1L);
        item.setFilePath("README");
        item.setLineNumber(2);
        item.setTargetType("line");
        item.setSuccess(true);
        item.setStatus("published");
        item.setMessage("GitHub comment published");
        item.setGithubUrl("https://github.com/comment/1");
        item.setGithubCommentId(101L);
        item.setPublishedAt(LocalDateTime.of(2026, 6, 9, 12, 0, 1));
        when(githubCommentPublicationBatchMapper.selectList(any())).thenReturn(List.of(batch));
        when(githubCommentPublicationBatchItemMapper.selectList(any())).thenReturn(List.of(item));

        var history = service.getGithubCommentPublicationHistory(521L);

        assertThat(history.taskId()).isEqualTo(521L);
        assertThat(history.batches()).hasSize(1);
        assertThat(history.batches().getFirst().batchId()).isEqualTo(10L);
        assertThat(history.batches().getFirst().items()).hasSize(1);
        assertThat(history.batches().getFirst().items().getFirst().status()).isEqualTo("published");
        assertThat(history.batches().getFirst().items().getFirst().url()).isEqualTo("https://github.com/comment/1");
    }

    @Test
    void getGithubCommentPublicationHistoryAddsReadableFailureForTokenError() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        GithubCommentPublicationBatch batch = new GithubCommentPublicationBatch();
        batch.setId(11L);
        batch.setTaskId(521L);
        batch.setStatus("failed");
        batch.setTotalFindings(1);
        batch.setAttemptedCount(1);
        batch.setSucceededCount(0);
        batch.setFailedCount(1);
        batch.setSkippedCount(0);
        batch.setCreatedAt(LocalDateTime.of(2026, 6, 9, 12, 0));
        batch.setCompletedAt(LocalDateTime.of(2026, 6, 9, 12, 0, 1));
        GithubCommentPublicationBatchItem item = new GithubCommentPublicationBatchItem();
        item.setBatchId(11L);
        item.setTaskId(521L);
        item.setFindingId(1L);
        item.setFilePath("README");
        item.setLineNumber(2);
        item.setTargetType("line");
        item.setSuccess(false);
        item.setStatus("failed");
        item.setMessage("401 Bad credentials");
        when(githubCommentPublicationBatchMapper.selectList(any())).thenReturn(List.of(batch));
        when(githubCommentPublicationBatchItemMapper.selectList(any())).thenReturn(List.of(item));

        var history = service.getGithubCommentPublicationHistory(521L);

        var historyItem = history.batches().getFirst().items().getFirst();
        assertThat(historyItem.failureCategory()).isEqualTo("github_token_invalid");
        assertThat(historyItem.failureReason()).isEqualTo("GitHub Token 无效或已过期");
        assertThat(historyItem.failureSuggestion()).contains("更新 GitHub Token");
    }

    @Test
    void listReviewsFiltersBySourceAndTriggerSource() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
        Page<ReviewTask> page = Page.of(1, 20);
        page.setRecords(List.of(task()));
        page.setTotal(1);
        when(reviewTaskMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.listReviews(new ReviewQuery(
            1,
            20,
            null,
            null,
            null,
            "github_pr_picker",
            "existing_reused",
            null
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reviewTaskMapper).selectPage(any(), wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("source", "trigger_source");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
            .contains("GITHUB_PR_PICKER", "EXISTING_REUSED");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().getFirst().source()).isEqualTo("github_pr_picker");
    }

    @Test
    void listReviewsAddsReadableFailureSummaryFromTimeline() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
        ReviewTask failedTask = task();
        failedTask.setStatus("FAILED");
        failedTask.setRiskLevel("HIGH");
        failedTask.setLlmStatus("FAILED");
        Page<ReviewTask> page = Page.of(1, 20);
        page.setRecords(List.of(failedTask));
        page.setTotal(1);
        when(reviewTaskMapper.selectPage(any(), any())).thenReturn(page);
        when(reviewTimelineMapper.selectList(any())).thenReturn(List.of(timeline("Review failed: 401 Bad credentials")));

        var result = service.listReviews(new ReviewQuery(1, 20, null, null, null, null, null, null));

        assertThat(result.items().getFirst().failureCategory()).isEqualTo("github_token_invalid");
        assertThat(result.items().getFirst().failureReason()).isEqualTo("GitHub Token 无效或已过期");
        assertThat(result.items().getFirst().failureSuggestion()).contains("更新 GitHub Token");
    }

    @Test
    void getReviewDetailAddsReadableFailureSummaryFromTimeline() {
        ReviewTask failedTask = task();
        failedTask.setStatus("FAILED");
        failedTask.setRiskLevel("HIGH");
        failedTask.setLlmStatus("FAILED");
        when(reviewTaskMapper.selectById(521L)).thenReturn(failedTask);
        when(changedFileMapper.selectList(any())).thenReturn(List.of());
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());
        when(reviewTimelineMapper.selectList(any())).thenReturn(List.of(
            timeline("Task queued"),
            timeline("Review failed: 403 Resource not accessible by integration")
        ));

        var result = service.getReviewDetail(521L);

        assertThat(result.failureCategory()).isEqualTo("github_permission_denied");
        assertThat(result.failureReason()).isEqualTo("GitHub Token 权限不足");
        assertThat(result.failureSuggestion()).contains("目标仓库和 PR");
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
            "master",
            "github_pr_picker"
        ));

        assertThat(result.taskId()).isEqualTo(521L);
        assertThat(result.existing()).isTrue();
        assertThat(result.triggerSource()).isEqualTo("existing_reused");
        assertThat(result.message()).isEqualTo("Review task already exists");
        verify(reviewTaskMapper, never()).insert(any(ReviewTask.class));
        verify(reviewTaskMapper).updateById(any(ReviewTask.class));
        verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));
    }

    @Test
    void triggerManualReviewStoresGithubPrPickerSourceForNewTask() {
        when(reviewTaskMapper.selectOne(any())).thenReturn(null);

        var result = service.triggerManualReview(new ManualReviewRequest(
            "octocat",
            "Hello-World",
            1,
            "Smoke review",
            "public-pr-2",
            "master",
            "github_pr_picker"
        ));

        assertThat(result.existing()).isFalse();
        assertThat(result.source()).isEqualTo("github_pr_picker");
        assertThat(result.triggerSource()).isEqualTo("github_pr_picker");
        verify(reviewTaskMapper).insert(org.mockito.Mockito.argThat((ReviewTask task) ->
            "GITHUB_PR_PICKER".equals(task.getSource()) && "GITHUB_PR_PICKER".equals(task.getTriggerSource())
        ));
        verify(reviewTaskPublisher).publish(any(ReviewTaskMessage.class));
    }

    @Test
    void retryReviewQueuesFailedTaskAndPublishesMessage() {
        ReviewTask task = task();
        task.setStatus("FAILED");
        task.setRiskLevel("HIGH");
        task.setMqRetries(2);
        task.setLlmStatus("FAILED");
        ReviewTimeline latestTimeline = new ReviewTimeline();
        latestTimeline.setSortOrder(5);
        when(reviewTaskMapper.selectById(521L)).thenReturn(task);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latestTimeline);

        var result = service.retryReview(521L);

        assertThat(result.status()).isEqualTo("queued");
        assertThat(result.retryCount()).isEqualTo(3);

        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(reviewTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("QUEUED");
        assertThat(taskCaptor.getValue().getLlmStatus()).isEqualTo("PENDING");
        assertThat(taskCaptor.getValue().getRiskLevel()).isEqualTo("INFO");
        assertThat(taskCaptor.getValue().getMqRetries()).isEqualTo(3);

        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(timelineCaptor.capture());
        assertThat(timelineCaptor.getValue().getLabel()).isEqualTo("Retry queued");
        assertThat(timelineCaptor.getValue().getStatus()).isEqualTo("CURRENT");
        assertThat(timelineCaptor.getValue().getSortOrder()).isEqualTo(6);

        ArgumentCaptor<ReviewTaskMessage> messageCaptor = ArgumentCaptor.forClass(ReviewTaskMessage.class);
        verify(reviewTaskPublisher).publish(messageCaptor.capture());
        assertThat(messageCaptor.getValue().taskId()).isEqualTo(521L);
        assertThat(messageCaptor.getValue().commit()).isEqualTo("public-pr-1-llm-string-response");
    }

    @Test
    void retryReviewRejectsNonFailedTask() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());

        assertThatThrownBy(() -> service.retryReview(521L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only failed review tasks can be retried");

        verify(reviewTaskMapper, never()).updateById(any(ReviewTask.class));
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
        task.setSource("GITHUB_PR_PICKER");
        task.setTriggerSource("GITHUB_PR_PICKER");
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

    private ReviewTimeline timeline(String label) {
        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(521L);
        timeline.setLabel(label);
        timeline.setEventTime(LocalDateTime.of(2026, 6, 9, 12, 0));
        timeline.setStatus(label.startsWith("Review failed") ? "FAILED" : "DONE");
        timeline.setSortOrder(label.startsWith("Review failed") ? 5 : 1);
        return timeline;
    }

    private IntegrationConfig githubConfig(String owner, String repository, String status, String token, String lastError) {
        IntegrationConfig config = new IntegrationConfig();
        config.setProvider("GITHUB");
        config.setDefaultOwner(owner);
        config.setDefaultRepo(repository);
        config.setStatus(status);
        config.setTokenValue(token);
        config.setLastError(lastError);
        return config;
    }
}
