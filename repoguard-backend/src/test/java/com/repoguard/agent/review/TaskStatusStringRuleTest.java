package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskStatusStringRuleTest {

    private final TaskStatusStringRule rule = new TaskStatusStringRule(new ReviewFindingFactory());

    @Test
    void evaluatesTaskStatusStringWrite() {
        var finding = rule.evaluate(context("src/ReviewTaskService.java", "task.setStatus(\"REVIEWING\");", Map.of()));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-STATE-001");
        assertThat(finding.get().severity()).isEqualTo("MEDIUM");
        assertThat(finding.get().filePath()).isEqualTo("src/ReviewTaskService.java");
        assertThat(finding.get().lineNumber()).isEqualTo(33);
        assertThat(finding.get().reviewDimension()).isEqualTo("PROJECT_RULE");
    }

    @Test
    void evaluatesSupportedStatusSetterAndStatusValues() {
        assertThat(rule.evaluate(context("src/App.java", "task.setStatus(\"QUEUED\");", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "task.setReviewStatus(\"COMPLETED\");", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "task.setHumanReviewStatus(\"FAILED\");", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "task.setStatus(\"PUBLISH_FAILED\");", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "task.setStatus(\"PENDING_HUMAN_REVIEW\");", Map.of()))).isPresent();
    }

    @Test
    void skipsWhenLineDoesNotWriteKnownStatusString() {
        assertThat(rule.evaluate(context("src/App.java", "task.setStatus(status);", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "task.setDisplayStatus(\"REVIEWING\");", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "task.setStatus(\"UNKNOWN\");", Map.of()))).isEmpty();
    }

    @Test
    void skipsWhenRuleIsDisabled() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            TaskStatusStringRule.RULE_ID,
            new ReviewRuleSettings(TaskStatusStringRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(context("src/App.java", "task.setStatus(\"REVIEWING\");", rules))).isEmpty();
    }

    @Test
    void skipsWhenFilePatternDoesNotMatch() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            TaskStatusStringRule.RULE_ID,
            new ReviewRuleSettings(TaskStatusStringRule.RULE_ID, "ENABLED", "*.java")
        );

        assertThat(rule.evaluate(context("docs/README.md", "task.setStatus(\"REVIEWING\");", rules))).isEmpty();
    }

    private ReviewRuleLineContext context(String filePath, String line, Map<String, ReviewRuleSettings> configuredRules) {
        return new ReviewRuleLineContext(filePath, 33, line, line.trim(), configuredRules);
    }
}
