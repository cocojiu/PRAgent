package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RawExternalCallRuleTest {

    private final RawExternalCallRule rule = new RawExternalCallRule(new RuleMatchFactory());

    @Test
    void evaluatesRestClientRetrieveCall() {
        var finding = rule.evaluate(context(
            "src/ProfileClient.java",
            "return restClient.get().uri(url).retrieve().body(Profile.class);",
            Map.of()
        ));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-EXT-001");
        assertThat(finding.get().filePath()).isEqualTo("src/ProfileClient.java");
        assertThat(finding.get().lineNumber()).isEqualTo(51);
        assertThat(finding.get().reviewDimension()).isEqualTo("EXTERNAL_CALL_RULE");
    }

    @Test
    void evaluatesSupportedClientAndTerminalOperations() {
        assertThat(rule.evaluate(context("src/App.java", "webClient.get().retrieve().bodyToMono(String.class);", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "new RestTemplate().exchange(url, method, entity, String.class);", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "HttpClient.newHttpClient().send(request, handler);", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "return restClient.get().body(Profile.class);", Map.of()))).isPresent();
    }

    @Test
    void skipsWhenLineDoesNotContainClientAndTerminalOperationTogether() {
        assertThat(rule.evaluate(context("src/App.java", "return restClient.get().uri(url);", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "return response.body();", Map.of()))).isEmpty();
    }

    @Test
    void skipsWhenRuleIsDisabled() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            RawExternalCallRule.RULE_ID,
            new ReviewRuleSettings(RawExternalCallRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(context(
            "src/App.java",
            "return restClient.get().uri(url).retrieve().body(Profile.class);",
            rules
        ))).isEmpty();
    }

    @Test
    void skipsWhenFilePatternDoesNotMatch() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            RawExternalCallRule.RULE_ID,
            new ReviewRuleSettings(RawExternalCallRule.RULE_ID, "ENABLED", "*.java")
        );

        assertThat(rule.evaluate(context(
            "docs/README.md",
            "return restClient.get().uri(url).retrieve().body(Profile.class);",
            rules
        ))).isEmpty();
    }

    private ReviewRuleLineContext context(String filePath, String line, Map<String, ReviewRuleSettings> configuredRules) {
        return new ReviewRuleLineContext(
            filePath,
            51,
            line,
            line.trim(),
            ReviewRuleTestFixtures.configuredOrDefault(rule.id(), configuredRules)
        );
    }
}
