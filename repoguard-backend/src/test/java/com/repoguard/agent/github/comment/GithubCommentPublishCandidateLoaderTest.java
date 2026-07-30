package com.repoguard.agent.github.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.GithubCommentPreviewFindingStat;
import com.repoguard.agent.mapper.projection.ReviewFindingProjections.SeverityCounts;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.review.task.ReviewTaskListItemAssembler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GithubCommentPublishCandidateLoaderTest {

    private final ChangedFileMapper changedFileMapper = Mockito.mock(ChangedFileMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = Mockito.mock(ReviewFindingMapper.class);
    private final GithubCommentPublicationMapper publicationMapper = Mockito.mock(GithubCommentPublicationMapper.class);
    private final GithubCommentPublishCandidateLoader loader = new GithubCommentPublishCandidateLoader(
        changedFileMapper,
        reviewFindingMapper,
        new GithubCommentPreviewPublicationLoader(publicationMapper),
        new GithubCommentPreviewItemBuilder(),
        new ReviewTaskListItemAssembler(),
        new ReviewRiskProfileBuilder(),
        new PrReviewSummaryBuilder()
    );

    @Test
    void loadOverviewBuildsPrSummaryFromAggregateData() {
        GithubCommentPreviewFindingStat stat = new GithubCommentPreviewFindingStat(3L, 3L, 0L);
        when(reviewFindingMapper.selectGithubCommentPreviewFindingStat(521L)).thenReturn(stat);
        when(reviewFindingMapper.selectFindingSeverityCounts(521L))
            .thenReturn(new SeverityCounts(0L, 1L, 1L, 1L, 0L));
        when(reviewFindingMapper.selectCount(any())).thenReturn(2L);
        when(changedFileMapper.selectCount(any())).thenReturn(4L);
        when(changedFileMapper.selectTopChangedFilesByChurn(521L, 3)).thenReturn(List.of(changedFile("src/App.java")));
        when(publicationMapper.selectOne(any())).thenReturn(null);

        var overview = loader.loadOverview(task());

        assertThat(overview.totalFindings()).isEqualTo(3);
        assertThat(overview.prSummaryCandidate()).isNotNull();
        assertThat(overview.prSummaryCandidate().targetType()).isEqualTo("pull_request");
        assertThat(overview.prSummaryCandidate().commentBody()).contains("3 条审查发现");
    }

    @Test
    void loadFindingCandidatesUsesKeysetQueryAndCandidateFilesOnly() {
        ReviewFinding lineFinding = finding(101L, "README.md", 8);
        ReviewFinding prFinding = finding(102L, "docs/missing.md", 12);
        when(reviewFindingMapper.selectGithubCommentPublishCandidatesAfterId(521L, 100L, 50))
            .thenReturn(List.of(lineFinding, prFinding));
        when(changedFileMapper.selectList(any())).thenReturn(List.of(changedFile("README.md")));
        when(publicationMapper.selectList(any())).thenReturn(List.of());

        var items = loader.loadFindingCandidates(521L, 100L, 50);

        assertThat(items).hasSize(2);
        assertThat(items.getFirst().findingId()).isEqualTo(101L);
        assertThat(items.getFirst().targetType()).isEqualTo("line");
        assertThat(items.getLast().findingId()).isEqualTo(102L);
        assertThat(items.getLast().targetType()).isEqualTo("pull_request");
        verify(reviewFindingMapper).selectGithubCommentPublishCandidatesAfterId(521L, 100L, 50);
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(521L);
        task.setPrNumber(1);
        task.setTitle("Publish review");
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

    private ChangedFile changedFile(String path) {
        ChangedFile file = new ChangedFile();
        file.setId(1L);
        file.setTaskId(521L);
        file.setFilePath(path);
        file.setChangeType("MODIFY");
        file.setAdditions(6);
        file.setDeletions(1);
        return file;
    }

    private ReviewFinding finding(Long id, String file, Integer line) {
        ReviewFinding finding = new ReviewFinding();
        finding.setId(id);
        finding.setTaskId(521L);
        finding.setCategory("FINDING");
        finding.setSeverity("LOW");
        finding.setFilePath(file);
        finding.setLineNumber(line);
        finding.setMessage("message");
        finding.setRecommendation("recommendation");
        return finding;
    }
}
