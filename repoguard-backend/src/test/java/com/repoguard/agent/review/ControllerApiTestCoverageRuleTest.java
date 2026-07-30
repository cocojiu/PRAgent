package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControllerApiTestCoverageRuleTest {

    private final ControllerApiTestCoverageRule rule =
        new ControllerApiTestCoverageRule(new ReviewFindingFactory());

    @Test
    void detectsControllerMappingChangeWithoutTestCoverage() {
        PullRequestDiff diff = diff(List.of(file(
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
        PullRequestDiff diff = diff(List.of(
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
        PullRequestDiff diff = diff(List.of(file(
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

    private PullRequestDiff diff(List<PullRequestChangedFile> files) {
        return new PullRequestDiff("octocat", "Hello-World", 1, files);
    }

    private PullRequestChangedFile file(String filename, String patch) {
        return new PullRequestChangedFile(filename, "modified", 1, 0, patch);
    }
}
