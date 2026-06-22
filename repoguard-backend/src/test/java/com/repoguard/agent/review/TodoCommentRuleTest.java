package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TodoCommentRuleTest {

    private final TodoCommentRule rule = new TodoCommentRule(new ReviewFindingFactory());

    @Test
    void evaluatesTodoComment() {
        var finding = rule.evaluate(context("src/App.java", "// TODO implement retry", Map.of()));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-GEN-001");
        assertThat(finding.get().severity()).isEqualTo("LOW");
        assertThat(finding.get().filePath()).isEqualTo("src/App.java");
        assertThat(finding.get().lineNumber()).isEqualTo(33);
    }

    @Test
    void evaluatesFixmeComment() {
        var finding = rule.evaluate(context("src/App.java", "// FIXME handle null", Map.of()));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-GEN-001");
    }

    @Test
    void skipsWhenRuleIsDisabled() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            TodoCommentRule.RULE_ID,
            new ReviewRuleSettings(TodoCommentRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(context("src/App.java", "// TODO implement retry", rules))).isEmpty();
    }

    @Test
    void skipsWhenFilePatternDoesNotMatch() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            TodoCommentRule.RULE_ID,
            new ReviewRuleSettings(TodoCommentRule.RULE_ID, "ENABLED", "*.java")
        );

        assertThat(rule.evaluate(context("docs/README.md", "// TODO implement retry", rules))).isEmpty();
    }

    private ReviewRuleLineContext context(String filePath, String line, Map<String, ReviewRuleSettings> configuredRules) {
        return new ReviewRuleLineContext(filePath, 33, line, line.trim(), configuredRules);
    }
}
