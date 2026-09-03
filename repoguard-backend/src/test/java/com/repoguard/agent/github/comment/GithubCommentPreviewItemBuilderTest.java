package com.repoguard.agent.github.comment;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewFinding;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubCommentPreviewItemBuilderTest {

    private final GithubCommentPreviewItemBuilder builder = new GithubCommentPreviewItemBuilder();

    @Test
    void buildFindingItemCreatesLineCommentForChangedFileLineFinding() {
        var item = builder.buildFindingItem(
            finding("HIGH", "src/App.java", 12, "Use structured logging", "Replace stdout", null),
            changedFile("src/App.java", "MODIFY"),
            null
        );

        assertThat(item.targetType()).isEqualTo("line");
        assertThat(item.commentable()).isTrue();
        assertThat(item.reason()).isNull();
        assertThat(item.commentBody())
            .contains("**RepoGuard HIGH finding**")
            .contains("**建议**：Replace stdout");
    }

    @Test
    void buildFindingItemIncludesSafeGithubSuggestionForExplicitReplacement() {
        ReviewFinding finding = finding(
            "HIGH", "src/App.java", 12, "Use structured logging", "Replace stdout", null
        );
        finding.setFixExample("```java\nlogger.info(\"value={}\", value);\n```");

        var item = builder.buildFindingItem(finding, changedFile("src/App.java", "MODIFY"), null);

        assertThat(item.commentBody())
            .contains("**可应用修复（请先确认）**", "```suggestion", "logger.info(\"value={}\", value);")
            .doesNotContain("```java");
    }

    @Test
    void rejectsNaturalLanguageOrUnsafeSuggestionAndPrFallback() {
        ReviewFinding finding = finding(
            "HIGH", "src/App.java", 12, "Use structured logging", "Replace stdout", null
        );
        finding.setFixExample("Replace stdout with the project logger");
        var naturalLanguage = builder.buildFindingItem(finding, changedFile("src/App.java", "MODIFY"), null);
        assertThat(naturalLanguage.commentBody()).doesNotContain("```suggestion");

        finding.setFixExample("suggestion:\nline one\nline two\nline three\nline four\nline five\nline six");
        var tooManyLines = builder.buildFindingItem(finding, changedFile("src/App.java", "MODIFY"), null);
        assertThat(tooManyLines.commentBody()).doesNotContain("```suggestion");

        finding.setFixExample("suggestion:System.out.println(\"x\");");
        var deleted = builder.buildFindingItem(finding, changedFile("src/App.java", "DELETED"), null);
        assertThat(deleted.targetType()).isEqualTo("pull_request");
        assertThat(deleted.commentBody()).doesNotContain("```suggestion");
    }

    @Test
    void buildFindingItemFallsBackToPullRequestCommentWhenLineIsMissing() {
        var item = builder.buildFindingItem(
            finding("LOW", "README.md", null, "Missing line", "Add line reference", null),
            changedFile("README.md", "MODIFY"),
            null
        );

        assertThat(item.targetType()).isEqualTo("pull_request");
        assertThat(item.reason()).isEqualTo("Finding is missing a valid line number and will be posted as a PR comment");
    }

    @Test
    void buildFindingItemKeepsFalsePositiveVisibleButNotCommentable() {
        var item = builder.buildFindingItem(
            finding("LOW", "README.md", 8, "Known safe", "No change", "FALSE_POSITIVE"),
            changedFile("README.md", "MODIFY"),
            null
        );

        assertThat(item.commentable()).isFalse();
        assertThat(item.feedbackStatus()).isEqualTo("false_positive");
        assertThat(item.reason()).isEqualTo("Finding marked as false positive and will not be published");
    }

    @Test
    void buildFindingItemMarksPublishedPublication() {
        var item = builder.buildFindingItem(
            finding("LOW", "README.md", 8, "Use structured logging", "Replace stdout", null),
            changedFile("README.md", "MODIFY"),
            publication(1L, "line")
        );

        assertThat(item.published()).isTrue();
        assertThat(item.commentable()).isFalse();
        assertThat(item.reason()).isEqualTo("GitHub comment already published");
        assertThat(item.publishedAt()).isEqualTo("2026-06-18 11:00:00");
    }

    @Test
    void buildPrSummaryItemCreatesPullRequestCommentDraft() {
        PrReviewSummaryDto summary = new PrReviewSummaryDto(
            "low",
            "summary",
            "merge",
            true,
            false,
            List.of("risk"),
            List.of("README.md"),
            "## RepoGuard PR 总评"
        );

        var item = builder.buildPrSummaryItem(summary, null);

        assertThat(item.findingId()).isNull();
        assertThat(item.targetType()).isEqualTo("pull_request");
        assertThat(item.commentable()).isTrue();
        assertThat(item.file()).isEqualTo("PR 总评");
        assertThat(item.feedbackStatus()).isEqualTo("valid");
    }

    private ReviewFinding finding(
        String severity,
        String file,
        Integer line,
        String message,
        String recommendation,
        String feedbackStatus
    ) {
        ReviewFinding finding = new ReviewFinding();
        finding.setId(1L);
        finding.setSeverity(severity);
        finding.setRuleId("LLM");
        finding.setFilePath(file);
        finding.setLineNumber(line);
        finding.setMessage(message);
        finding.setRecommendation(recommendation);
        finding.setFeedbackStatus(feedbackStatus);
        return finding;
    }

    private ChangedFile changedFile(String path, String changeType) {
        ChangedFile file = new ChangedFile();
        file.setFilePath(path);
        file.setChangeType(changeType);
        return file;
    }

    private GithubCommentPublication publication(Long findingId, String targetType) {
        GithubCommentPublication publication = new GithubCommentPublication();
        publication.setFindingId(findingId);
        publication.setTargetType(targetType);
        publication.setSuccess(true);
        publication.setStatus("published");
        publication.setGithubUrl("https://github.com/comment/finding");
        publication.setMessage("GitHub comment published");
        publication.setPublishedAt(LocalDateTime.of(2026, 6, 18, 11, 0));
        return publication;
    }
}
