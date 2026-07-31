package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StandardOutputLoggingRuleTest {

    private final StandardOutputLoggingRule rule = new StandardOutputLoggingRule(new RuleMatchFactory());

    @Test
    void evaluatesStandardOutputLogging() {
        var finding = rule.evaluate(context("src/App.java", "System.out.println(\"debug\");", Map.of()));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-JAVA-002");
        assertThat(finding.get().filePath()).isEqualTo("src/App.java");
        assertThat(finding.get().lineNumber()).isEqualTo(12);
    }

    @Test
    void skipsWhenRuleIsDisabled() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            StandardOutputLoggingRule.RULE_ID,
            new ReviewRuleSettings(StandardOutputLoggingRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(context("src/App.java", "System.out.println(\"debug\");", rules))).isEmpty();
    }

    @Test
    void skipsWhenFilePatternDoesNotMatch() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            StandardOutputLoggingRule.RULE_ID,
            new ReviewRuleSettings(StandardOutputLoggingRule.RULE_ID, "ENABLED", "*.java")
        );

        assertThat(rule.evaluate(context("docs/README.md", "System.out.println(\"debug\");", rules))).isEmpty();
    }

    private ReviewRuleLineContext context(String filePath, String line, Map<String, ReviewRuleSettings> configuredRules) {
        return new ReviewRuleLineContext(
            filePath,
            12,
            line,
            line.trim(),
            ReviewRuleTestFixtures.configuredOrDefault(rule.id(), configuredRules)
        );
    }
}
