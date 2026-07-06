package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
import com.repoguard.agent.dto.GithubCommentPreviewFindingStat;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubCommentPreviewServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final GithubCommentPublicationMapper publicationMapper = org.mockito.Mockito.mock(
        GithubCommentPublicationMapper.class
    );
    private final GithubIntegrationProvider integrationProvider = org.mockito.Mockito.mock(
        GithubIntegrationProvider.class
    );
    private final GithubCommentPreviewServiceImpl service = new GithubCommentPreviewServiceImpl(
        reviewTaskMapper,
        integrationProvider,
        new ReviewRiskProfileBuilder(),
        new PrReviewSummaryBuilder(),
        new ReviewTaskListItemAssembler(),
        new GithubCommentPreviewDataLoader(changedFileMapper, reviewFindingMapper),
        new GithubCommentPreviewPublicationLoader(publicationMapper),
        new GithubCommentPreviewResponseAssembler(
            new GithubCommentWritebackCheckBuilder(),
            new GithubCommentPreviewItemBuilder()
        )
    );

    @Test
    void getPreviewBuildsSummaryAndLineOrPullRequestComments() {
        stubTaskAndSettings(settings("octocat", "Hello-World", "CONFIGURED", "ghp_test", null));
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README.md", "MODIFY")));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "HIGH", "README.md", 8, "Use structured logging", "Replace stdout"),
            finding(2L, "LOW", "README.md", null, "Missing line", "Add a line reference")
        ));
        when(publicationMapper.selectList(any())).thenReturn(List.of());

        var preview = service.getPreview(521L);

        assertThat(preview.totalFindings()).isEqualTo(2);
        assertThat(preview.commentableCount()).isEqualTo(3);
        assertThat(preview.blockedCount()).isZero();
        assertThat(preview.writebackCheck().status()).isEqualTo("ready");
        assertThat(preview.items()).extracting("targetType")
            .containsExactly("pull_request", "line", "pull_request");
        assertThat(preview.items().getFirst().commentBody()).contains("## RepoGuard PR 总评");
        assertThat(preview.items().get(1).commentBody())
            .contains("**RepoGuard HIGH finding**")
            .contains("**建议**：Replace stdout");
        assertThat(preview.items().getLast().reason())
            .isEqualTo("Finding is missing a valid line number and will be posted as a PR comment");
    }

    @Test
    void getPreviewSupportsPaginationAndCommentableFilter() {
        stubTaskAndSettings(settings("octocat", "Hello-World", "CONFIGURED", "ghp_test", null));
        ReviewFinding pageFinding = finding(3L, "MEDIUM", "src/App.java", 10, "Validate input", "Add validation");
        when(reviewFindingMapper.selectGithubCommentPreviewFindingStat(521L)).thenReturn(previewStat(3L, 1L, 1L));
        when(reviewFindingMapper.selectGithubCommentPreviewCommentableFindings(521L, 0L, 1))
            .thenReturn(List.of(pageFinding));
        when(reviewFindingMapper.selectFindingSeverityCounts(521L))
            .thenReturn(new FindingSeverityCountsDto(0L, 1L, 1L, 1L, 0L));
        when(reviewFindingMapper.selectCount(any())).thenReturn(0L);
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("src/App.java", "MODIFY")));
        when(changedFileMapper.selectTopChangedFilesByChurn(521L, 3)).thenReturn(List.of(
            changedFile("src/App.java", "MODIFY"),
            changedFile("README.md", "MODIFY")
        ));
        when(changedFileMapper.selectCount(any())).thenReturn(2L);
        when(publicationMapper.selectList(any())).thenReturn(List.of());

        var preview = service.getPreview(521L, 2, 1, true);

        assertThat(preview.totalFindings()).isEqualTo(3);
        assertThat(preview.commentableCount()).isEqualTo(2);
        assertThat(preview.publishedCount()).isEqualTo(1);
        assertThat(preview.blockedCount()).isEqualTo(1);
        assertThat(preview.itemTotal()).isEqualTo(2);
        assertThat(preview.page()).isEqualTo(2);
        assertThat(preview.pageSize()).isEqualTo(1);
        assertThat(preview.commentableOnly()).isTrue();
        assertThat(preview.items()).hasSize(1);
        assertThat(preview.items().getFirst().findingId()).isEqualTo(3L);
        assertThat(preview.items().getFirst().commentable()).isTrue();
        verify(reviewFindingMapper, never()).selectList(any());
        verify(reviewFindingMapper).selectGithubCommentPreviewCommentableFindings(eq(521L), eq(0L), eq(1));
    }

    @Test
    void getPreviewReportsRepositoryMismatchWithoutBlockingDraftConstruction() {
        stubTaskAndSettings(settings("another-owner", "another-repo", "CONFIGURED", "ghp_test", null));
        when(changedFileMapper.selectList(any())).thenReturn(List.of());
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());

        var preview = service.getPreview(521L);

        assertThat(preview.writebackCheck().status()).isEqualTo("repository_mismatch");
        assertThat(preview.writebackCheck().level()).isEqualTo("warning");
        assertThat(preview.writebackCheck().repositoryMatched()).isFalse();
        assertThat(preview.items()).hasSize(1);
    }

    @Test
    void getPreviewMarksExistingSummaryAndFindingPublicationsAsPublished() {
        stubTaskAndSettings(settings("octocat", "Hello-World", "CONFIGURED", "ghp_test", null));
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README.md", "MODIFY")));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "LOW", "README.md", 8, "Use structured logging", "Replace stdout")
        ));
        when(publicationMapper.selectList(any())).thenReturn(List.of(publication(1L, "line", "finding")));
        when(publicationMapper.selectOne(any())).thenReturn(publication(null, "pull_request", "summary"));

        var preview = service.getPreview(521L);

        assertThat(preview.commentableCount()).isZero();
        assertThat(preview.blockedCount()).isZero();
        assertThat(preview.items()).allMatch(item -> Boolean.TRUE.equals(item.published()));
        assertThat(preview.items()).extracting("reason")
            .containsOnly("GitHub comment already published");
    }

    @Test
    void getPreviewKeepsNonActionableFindingVisibleButNotCommentable() {
        stubTaskAndSettings(settings("octocat", "Hello-World", "CONFIGURED", "ghp_test", null));
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README.md", "MODIFY")));
        ReviewFinding finding = finding(1L, "LOW", "README.md", 8, "Known safe", "No change");
        finding.setFeedbackStatus("FALSE_POSITIVE");
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(finding));
        when(publicationMapper.selectList(any())).thenReturn(List.of());

        var preview = service.getPreview(521L);

        assertThat(preview.commentableCount()).isEqualTo(1);
        assertThat(preview.blockedCount()).isEqualTo(1);
        assertThat(preview.items().getLast().commentable()).isFalse();
        assertThat(preview.items().getLast().feedbackStatus()).isEqualTo("false_positive");
        assertThat(preview.items().getLast().reason())
            .isEqualTo("Finding marked as false positive and will not be published");
    }

    @Test
    void getPreviewRejectsMissingTask() {
        when(reviewTaskMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.getPreview(404L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Review task not found: 404");
    }

    private void stubTaskAndSettings(GithubIntegrationSettings settings) {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(integrationProvider.getSettings()).thenReturn(settings);
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(521L);
        task.setPrNumber(1);
        task.setTitle("Preview review");
        task.setRepository("Hello-World");
        task.setOrganization("octocat");
        task.setCommitSha("abc123");
        task.setBranchName("main");
        task.setStatus("COMPLETED");
        task.setRiskLevel("LOW");
        task.setMqRetries(0);
        task.setLlmStatus("COMPLETED");
        task.setPrUrl("https://github.com/octocat/Hello-World/pull/1");
        task.setSource("GITHUB_PR_PICKER");
        task.setTriggerSource("GITHUB_PR_PICKER");
        task.setCreatedAt(LocalDateTime.of(2026, 6, 18, 10, 0));
        task.setDurationSeconds(37);
        return task;
    }

    private ChangedFile changedFile(String path, String changeType) {
        ChangedFile file = new ChangedFile();
        file.setId(1L);
        file.setTaskId(521L);
        file.setFilePath(path);
        file.setChangeType(changeType);
        file.setAdditions(6);
        file.setDeletions(1);
        return file;
    }

    private ReviewFinding finding(
        Long id,
        String severity,
        String file,
        Integer line,
        String message,
        String recommendation
    ) {
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

    private GithubCommentPublication publication(Long findingId, String targetType, String suffix) {
        GithubCommentPublication publication = new GithubCommentPublication();
        publication.setTaskId(521L);
        publication.setFindingId(findingId);
        publication.setTargetType(targetType);
        publication.setSuccess(true);
        publication.setStatus("published");
        publication.setGithubUrl("https://github.com/comment/" + suffix);
        publication.setMessage("GitHub comment published");
        publication.setPublishedAt(LocalDateTime.of(2026, 6, 18, 11, 0));
        return publication;
    }

    private GithubCommentPreviewFindingStat previewStat(
        Long totalFindings,
        Long commentableFindings,
        Long publishedFindings
    ) {
        GithubCommentPreviewFindingStat stat = new GithubCommentPreviewFindingStat();
        stat.setTotalFindings(totalFindings);
        stat.setCommentableFindings(commentableFindings);
        stat.setPublishedFindings(publishedFindings);
        return stat;
    }

    private GithubIntegrationSettings settings(
        String owner,
        String repository,
        String status,
        String token,
        String lastError
    ) {
        return new GithubIntegrationSettings(
            "GITHUB",
            status,
            "https://api.github.com",
            token,
            lastError,
            owner,
            repository,
            1L
        );
    }
}
