package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GithubCommentDirectPublishRuleTest {

    private final GithubCommentDirectPublishRule rule = new GithubCommentDirectPublishRule(new ReviewFindingFactory());

    @Test
    void evaluatesDirectPullRequestCommentsPublishCall() {
        var finding = rule.evaluate(context(
            "src/main/java/com/example/github/CommentPublisher.java",
            "githubPullRequestClient.publishPullRequestComments(task, drafts);",
            Map.of()
        ));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-GH-001");
        assertThat(finding.get().severity()).isEqualTo("HIGH");
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
        return new ReviewRuleLineContext(filePath, 21, line, line.trim(), configuredRules);
    }
}
