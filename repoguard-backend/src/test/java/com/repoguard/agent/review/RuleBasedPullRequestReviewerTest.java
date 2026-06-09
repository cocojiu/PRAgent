package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedPullRequestReviewerTest {

    private final ReviewRuleConfigMapper reviewRuleConfigMapper = org.mockito.Mockito.mock(ReviewRuleConfigMapper.class);
    private final RuleBasedPullRequestReviewer reviewer = new RuleBasedPullRequestReviewer(reviewRuleConfigMapper);

    @Test
    void skipsDisabledRulesWhenReviewingPatch() {
        when(reviewRuleConfigMapper.selectList(any())).thenReturn(List.of(disabledRule("RG-JAVA-002")));

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

    private ReviewRuleConfig disabledRule(String id) {
        ReviewRuleConfig rule = new ReviewRuleConfig();
        rule.setId(id);
        rule.setStatus("DISABLED");
        return rule;
    }
}
