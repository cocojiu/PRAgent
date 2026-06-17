package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.ReviewRuleProvider;
import com.repoguard.agent.config.ReviewRuleSettings;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleBasedPullRequestReviewerTest {

    private final ReviewRuleProvider reviewRuleProvider = org.mockito.Mockito.mock(ReviewRuleProvider.class);
    private final RuleBasedPullRequestReviewer reviewer = new RuleBasedPullRequestReviewer(reviewRuleProvider);

    @Test
    void skipsDisabledRulesWhenReviewingPatch() {
        when(reviewRuleProvider.getRulesById()).thenReturn(Map.of("RG-JAVA-002", disabledRule("RG-JAVA-002")));

        ReviewResult result = reviewer.review(new GithubPullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(new GithubChangedFile(
                "src/App.java",
                "modified",
                2,
                0,
                """
                    @@ -1,1 +1,3 @@
                     class App {
                    +System.out.println("debug");
                    +Thread.sleep(1000);
                     }
                    """
            ))
        ));

        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .contains("RG-JAVA-003")
            .doesNotContain("RG-JAVA-002");
    }

    @Test
    void skipsRulesWhenFilePatternDoesNotMatch() {
        when(reviewRuleProvider.getRulesById())
            .thenReturn(Map.of("RG-JAVA-002", rule("RG-JAVA-002", "ENABLED", "*.java")));

        ReviewResult result = reviewer.review(new GithubPullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            List.of(new GithubChangedFile(
                "docs/README.md",
                "modified",
                1,
                0,
                """
                    @@ -1,1 +1,2 @@
                     # Demo
                    +System.out.println("debug");
                    """
            ))
        ));

        assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
            .doesNotContain("RG-JAVA-002");
    }

    private ReviewRuleSettings disabledRule(String id) {
        return rule(id, "DISABLED", "");
    }

    private ReviewRuleSettings rule(String id, String status, String filePatterns) {
        return new ReviewRuleSettings(id, status, filePatterns);
    }
}
