package com.repoguard.agent.github.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.comment.GithubCommentPreviewDataLoader.GithubCommentPreviewData;
import com.repoguard.agent.github.comment.GithubCommentPreviewPublicationLoader.GithubCommentPreviewPublicationData;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GithubCommentPreviewResponseAssemblerTest {

    private final GithubCommentPreviewResponseAssembler assembler = responseAssembler();

    @Test
    void constructorRejectsMissingPreviewItemBuilder() {
        assertThatThrownBy(() -> new GithubCommentPreviewResponseAssembler(
            new GithubCommentWritebackCheckBuilder(),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("previewItemBuilder");
    }

    @Test
    void assemblesPreviewResponseAndCountsCommentablePublishedAndBlockedItems() {
        ReviewTask task = task();
        ReviewFinding lineFinding = finding(1001L, "README.md", 8, "VALID");
        ReviewFinding skippedFinding = finding(1002L, "README.md", 9, "FALSE_POSITIVE");
        ChangedFile changedFile = changedFile("README.md", "MODIFY");
        GithubCommentPreviewData previewData = new GithubCommentPreviewData(
            Map.of("README.md", changedFile),
            List.of(lineFinding, skippedFinding),
            List.of(findingDto(1001L), findingDto(1002L)),
            List.of(),
            List.of(new ChangedFileDto("README.md", "MODIFY", 6, 1))
        );
        GithubCommentPreviewPublicationData publicationData = new GithubCommentPreviewPublicationData(
            Map.of(1001L, publication(1001L, "line", "finding")),
            null
        );

        var response = assembler.assemble(
            task,
            settings("octocat", "Hello-World"),
            previewData,
            prSummary(),
            publicationData
        );

        assertThat(response.taskId()).isEqualTo(521L);
        assertThat(response.totalFindings()).isEqualTo(2);
        assertThat(response.commentableCount()).isEqualTo(1);
        assertThat(response.blockedCount()).isEqualTo(1);
        assertThat(response.publishedCount()).isEqualTo(1);
        assertThat(response.itemTotal()).isEqualTo(3);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.pageSize()).isEqualTo(3);
        assertThat(response.commentableOnly()).isFalse();
        assertThat(response.items()).extracting("targetType").containsExactly("pull_request", "line", "line");
        assertThat(response.items().get(1).published()).isTrue();
        assertThat(response.items().get(2).commentable()).isFalse();
        assertThat(response.items().get(2).reason()).isEqualTo("Finding marked as false positive and will not be published");
        assertThat(response.writebackCheck().status()).isEqualTo("ready");
    }

    @Test
    void assemblesPaginatedCommentablePreviewWithFullCounts() {
        ReviewTask task = task();
        ReviewFinding publishedFinding = finding(1001L, "README.md", 8, "VALID");
        ReviewFinding skippedFinding = finding(1002L, "README.md", 9, "FALSE_POSITIVE");
        ReviewFinding commentableFinding = finding(1003L, "src/App.java", 10, "UNREVIEWED");
        ChangedFile readme = changedFile("README.md", "MODIFY");
        ChangedFile app = changedFile("src/App.java", "MODIFY");
        GithubCommentPreviewData previewData = new GithubCommentPreviewData(
            Map.of("README.md", readme, "src/App.java", app),
            List.of(publishedFinding, skippedFinding, commentableFinding),
            List.of(findingDto(1001L), findingDto(1002L), findingDto(1003L)),
            List.of(),
            List.of(new ChangedFileDto("README.md", "MODIFY", 6, 1), new ChangedFileDto("src/App.java", "MODIFY", 3, 0))
        );
        GithubCommentPreviewPublicationData publicationData = new GithubCommentPreviewPublicationData(
            Map.of(1001L, publication(1001L, "line", "finding")),
            null
        );

        var response = assembler.assemble(
            task,
            settings("octocat", "Hello-World"),
            previewData,
            prSummary(),
            publicationData,
            2,
            1,
            true
        );

        assertThat(response.commentableCount()).isEqualTo(2);
        assertThat(response.publishedCount()).isEqualTo(1);
        assertThat(response.blockedCount()).isEqualTo(1);
        assertThat(response.itemTotal()).isEqualTo(2);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.pageSize()).isEqualTo(1);
        assertThat(response.commentableOnly()).isTrue();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().findingId()).isEqualTo(1003L);
        assertThat(response.items().getFirst().commentBody()).contains("Use structured logging");
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(521L);
        task.setPrNumber(42);
        task.setPrUrl("https://github.com/octocat/Hello-World/pull/42");
        task.setOrganization("octocat");
        task.setRepository("Hello-World");
        return task;
    }

    private ReviewFinding finding(Long id, String file, Integer line, String feedbackStatus) {
        ReviewFinding finding = new ReviewFinding();
        finding.setId(id);
        finding.setSeverity("HIGH");
        finding.setRuleId("LLM");
        finding.setFilePath(file);
        finding.setLineNumber(line);
        finding.setMessage("Use structured logging");
        finding.setRecommendation("Replace stdout");
        finding.setFeedbackStatus(feedbackStatus);
        return finding;
    }

    private ReviewFindingDto findingDto(Long id) {
        return new ReviewFindingDto(
            id,
            "high",
            "README.md",
            8,
            "Use structured logging",
            "Replace stdout",
            "HIGH",
            "Rule hit",
            "Avoid noisy production output",
            "Use logger",
            true,
            "PROJECT_RULE",
            "valid",
            null,
            null,
            null
        );
    }

    private ChangedFile changedFile(String path, String changeType) {
        ChangedFile file = new ChangedFile();
        file.setFilePath(path);
        file.setChangeType(changeType);
        return file;
    }

    private GithubCommentPublication publication(Long findingId, String targetType, String suffix) {
        GithubCommentPublication publication = new GithubCommentPublication();
        publication.setFindingId(findingId);
        publication.setTargetType(targetType);
        publication.setSuccess(true);
        publication.setStatus("published");
        publication.setGithubUrl("https://github.com/comment/" + suffix);
        publication.setMessage("GitHub comment published");
        publication.setPublishedAt(LocalDateTime.of(2026, 6, 19, 10, 40));
        return publication;
    }

    private PrReviewSummaryDto prSummary() {
        return new PrReviewSummaryDto(
            "high",
            "High risk changes",
            "request_changes",
            false,
            true,
            List.of("security"),
            List.of("README.md"),
            "## RepoGuard PR 总评"
        );
    }

    private GithubIntegrationSettings settings(String owner, String repository) {
        return new GithubIntegrationSettings(
            "GITHUB",
            "CONFIGURED",
            "https://api.github.com",
            "ghp_test",
            null,
            owner,
            repository,
            1L
        );
    }

    private GithubCommentPreviewResponseAssembler responseAssembler() {
        return new GithubCommentPreviewResponseAssembler(
            new GithubCommentWritebackCheckBuilder(),
            new GithubCommentPreviewItemBuilder()
        );
    }
}
