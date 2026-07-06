package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.ReviewRuleProvider;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ReviewRulePluginRegistrationTest {

    @Test
    void springCollectsReviewRulePluginsForRuleBasedReviewer() {
        ReviewRuleProvider reviewRuleProvider = org.mockito.Mockito.mock(ReviewRuleProvider.class);
        when(reviewRuleProvider.getRulesById()).thenReturn(Map.of());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ReviewRuleProvider.class, () -> reviewRuleProvider);
            context.register(
                ReviewFindingFactory.class,
                BroadExceptionCatchRule.class,
                StandardOutputLoggingRule.class,
                FixedSleepRule.class,
                TodoCommentRule.class,
                SensitiveLiteralRule.class,
                SensitiveLoggingRule.class,
                TaskStatusStringRule.class,
                RabbitMessagePublishRule.class,
                RawExternalCallRule.class,
                DestructiveMigrationRule.class,
                RequiredColumnWithoutDefaultRule.class,
                GithubCommentDirectPublishRule.class,
                ControllerApiTestCoverageRule.class,
                RuleBasedPullRequestReviewer.class
            );
            context.refresh();

            assertThat(context.getBeansOfType(ReviewRule.class)).hasSize(12);
            assertThat(context.getBeansOfType(PullRequestReviewRule.class)).hasSize(1);
            RuleBasedPullRequestReviewer reviewer = context.getBean(RuleBasedPullRequestReviewer.class);

            ReviewResult result = reviewer.review(new GithubPullRequestDiff(
                "octocat",
                "Hello-World",
                1,
                List.of(new GithubChangedFile(
                    "src/App.java",
                    "modified",
                    1,
                    0,
                    """
                        @@ -1,1 +1,2 @@
                         class App {
                        +System.out.println("debug");
                        """
                ))
            ));

            assertThat(result.findings()).extracting(ReviewFindingResult::ruleId)
                .containsExactly("RG-JAVA-002");
        }
    }
}
