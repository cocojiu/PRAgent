package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SensitiveLoggingRuleTest {

    private final SensitiveLoggingRule rule = new SensitiveLoggingRule(new RuleMatchFactory());

    @Test
    void evaluatesSensitiveLogStatement() {
        var finding = rule.evaluate(context("src/AuditLogger.java", "log.info(\"webhook secret {}\", webhookSecret);", Map.of()));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-LOG-001");
        assertThat(finding.get().filePath()).isEqualTo("src/AuditLogger.java");
        assertThat(finding.get().lineNumber()).isEqualTo(22);
        assertThat(finding.get().reviewDimension()).isEqualTo("SECURITY_RULE");
    }

    @Test
    void evaluatesLoggerAliasAndSupportedSensitiveKeywords() {
        assertThat(rule.evaluate(context("src/App.java", "logger.warn(\"token {}\", token);", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "logger.warn(\"password {}\", password);", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "logger.warn(\"apiKey {}\", apiKey);", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "logger.warn(\"api_key {}\", apiKey);", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "logger.warn(\"webhook {}\", webhook);", Map.of()))).isPresent();
    }

    @Test
    void skipsWhenLineIsNotSensitiveLogging() {
        assertThat(rule.evaluate(context("src/App.java", "log.info(\"review completed\");", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "String token = \"plain-token\";", Map.of()))).isEmpty();
    }

    @Test
    void skipsWhenRuleIsDisabled() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            SensitiveLoggingRule.RULE_ID,
            new ReviewRuleSettings(SensitiveLoggingRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(context("src/App.java", "log.info(\"token {}\", token);", rules))).isEmpty();
    }

    @Test
    void skipsWhenFilePatternDoesNotMatch() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            SensitiveLoggingRule.RULE_ID,
            new ReviewRuleSettings(SensitiveLoggingRule.RULE_ID, "ENABLED", "*.java")
        );

        assertThat(rule.evaluate(context("docs/README.md", "log.info(\"token {}\", token);", rules))).isEmpty();
    }

    private ReviewRuleLineContext context(String filePath, String line, Map<String, ReviewRuleSettings> configuredRules) {
        return new ReviewRuleLineContext(
            filePath,
            22,
            line,
            line.trim(),
            ReviewRuleTestFixtures.configuredOrDefault(rule.id(), configuredRules)
        );
    }
}
