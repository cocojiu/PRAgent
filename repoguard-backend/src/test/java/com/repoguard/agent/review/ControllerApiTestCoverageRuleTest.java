package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.ReviewRuleSettings;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControllerApiTestCoverageRuleTest {

    private final ControllerApiTestCoverageRule rule =
        new ControllerApiTestCoverageRule(new ReviewFindingFactory());

    @Test
    void detectsControllerMappingChangeWithoutTestCoverage() {
        GithubPullRequestDiff diff = diff(List.of(file(
            "src/main/java/com/example/order/OrderController.java",
            """
                @@ -18,0 +19,1 @@
                +@GetMapping("/orders/{id}")
                """
        )));

        assertThat(rule.evaluate(diff, Map.of()))
            .singleElement()
            .satisfies(finding -> {
                assertThat(finding.ruleId()).isEqualTo(ControllerApiTestCoverageRule.RULE_ID);
                assertThat(finding.lineNumber()).isEqualTo(19);
                assertThat(finding.severity()).isEqualTo("MEDIUM");
            });
    }

    @Test
    void skipsWhenPullRequestContainsControllerTestChange() {
        GithubPullRequestDiff diff = diff(List.of(
            file(
                "src/main/java/com/example/order/OrderController.java",
                """
                    @@ -18,0 +19,1 @@
                    +@GetMapping("/orders/{id}")
                    """
            ),
            file(
                "src/test/java/com/example/order/OrderControllerTest.java",
                """
                    @@ -30,0 +31,1 @@
                    +mockMvc.perform(get("/orders/1")).andExpect(status().isOk());
                    """
            )
        ));

        assertThat(rule.evaluate(diff, Map.of())).isEmpty();
    }

    @Test
    void honorsDisabledRuleConfiguration() {
        GithubPullRequestDiff diff = diff(List.of(file(
            "src/main/java/com/example/order/OrderController.java",
            """
                @@ -18,0 +19,1 @@
                +@PostMapping("/orders")
                """
        )));
        Map<String, ReviewRuleSettings> configuredRules = Map.of(
            ControllerApiTestCoverageRule.RULE_ID,
            new ReviewRuleSettings(ControllerApiTestCoverageRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(diff, configuredRules)).isEmpty();
    }

    private GithubPullRequestDiff diff(List<GithubChangedFile> files) {
        return new GithubPullRequestDiff("octocat", "Hello-World", 1, files);
    }

    private GithubChangedFile file(String filename, String patch) {
        return new GithubChangedFile(filename, "modified", 1, 0, patch);
    }
}
