package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GithubCommentDirectPublishRuleTest {

    private final GithubCommentDirectPublishRule rule = new GithubCommentDirectPublishRule(new RuleMatchFactory());

    @Test
    void evaluatesDirectPullRequestCommentsPublishCall() {
        var finding = rule.evaluate(context(
            "src/main/java/com/example/github/CommentPublisher.java",
            "githubPullRequestClient.publishPullRequestComments(task, drafts);",
            Map.of()
        ));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-GH-001");
        assertThat(finding.get().filePath()).isEqualTo("src/main/java/com/example/github/CommentPublisher.java");
        assertThat(finding.get().lineNumber()).isEqualTo(21);
        assertThat(finding.get().reviewDimension()).isEqualTo("GITHUB_WRITEBACK_RULE");
    }

    @Test
    void evaluatesSupportedPublishMethodsAndApiPaths() {
        assertThat(rule.evaluate(context("src/App.java", "client.publishPullRequestComment(task, body);", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "client.publishLineComment(task, line);", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "post(\"/pulls/{pullNumber}/comments\", body);", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "post(\"/issues/{pullNumber}/comments\", body);", Map.of()))).isPresent();
    }

    @Test
    void skipsWhenLineDoesNotDirectlyPublishGithubComment() {
        assertThat(rule.evaluate(context("src/App.java", "previewPullRequestComments(task);", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "publicationRecorder.record(batch);", Map.of()))).isEmpty();
    }

    @Test
    void skipsApprovedPublicationGatewayAndVisibleIdempotencyFence() {
        assertThat(rule.evaluate(context(
            "src/main/java/com/repoguard/agent/github/comment/GithubReviewBatchPublisher.java",
            "githubClient.publishPullRequestComments(task, drafts);",
            Map.of()
        ))).isEmpty();
        assertThat(rule.evaluate(context(
            "src/main/java/com/example/review/ReviewService.java",
            "githubClient.publishPullRequestComments(task, drafts);",
            Map.of(),
            ChangedFileContext.available(
                "src/main/java/com/example/review/ReviewService.java",
                "head",
                """
                    class ReviewService {
                        PublicationStore publicationStore;
                        void publish() {
                            publicationStore.findPublishedFindingIds(task.id());
                            githubClient.publishPullRequestComments(task, drafts);
                        }
                    }
                    """
            )
        ))).isEmpty();
    }

    @Test
    void marksDirectCommentCandidateUnverifiedWhenFullContextIsUnavailable() {
        var finding = rule.evaluate(context(
            "src/main/java/com/example/review/ReviewService.java",
            "githubClient.publishPullRequestComments(task, drafts);",
            Map.of(),
            ChangedFileContext.status(
                "src/main/java/com/example/review/ReviewService.java",
                "head",
                ChangedFileContext.Status.UNAVAILABLE,
                "fetch_failed"
            )
        ));

        assertThat(finding).isPresent();
        assertThat(finding.get().evidenceVerified()).isFalse();
    }

    @Test
    void skipsWhenRuleIsDisabled() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            GithubCommentDirectPublishRule.RULE_ID,
            new ReviewRuleSettings(GithubCommentDirectPublishRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(context("src/App.java", "client.publishLineComment(task, line);", rules))).isEmpty();
    }

    @Test
    void skipsWhenFilePatternDoesNotMatch() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            GithubCommentDirectPublishRule.RULE_ID,
            new ReviewRuleSettings(GithubCommentDirectPublishRule.RULE_ID, "ENABLED", "*.java")
        );

        assertThat(rule.evaluate(context("docs/README.md", "client.publishLineComment(task, line);", rules))).isEmpty();
    }

    private ReviewRuleLineContext context(String filePath, String line, Map<String, ReviewRuleSettings> configuredRules) {
        return context(filePath, line, configuredRules, ChangedFileContext.notRequested(filePath));
    }

    private ReviewRuleLineContext context(
        String filePath,
        String line,
        Map<String, ReviewRuleSettings> configuredRules,
        ChangedFileContext changedFileContext
    ) {
        return new ReviewRuleLineContext(
            filePath,
            21,
            line,
            line.trim(),
            ReviewRuleTestFixtures.configuredOrDefault(rule.id(), configuredRules),
            false,
            changedFileContext,
            "@@ -21,0 +21,1 @@\n+" + line
        );
    }
}
