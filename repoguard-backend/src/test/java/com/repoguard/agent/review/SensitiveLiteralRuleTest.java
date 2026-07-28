package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SensitiveLiteralRuleTest {

    private final SensitiveLiteralRule rule = new SensitiveLiteralRule(new ReviewFindingFactory());

    @Test
    void evaluatesSensitiveLiteral() {
        var finding = rule.evaluate(context("src/App.java", "String githubToken = \"plain-token\";", Map.of()));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-SECRET-001");
        assertThat(finding.get().severity()).isEqualTo("HIGH");
        assertThat(finding.get().filePath()).isEqualTo("src/App.java");
        assertThat(finding.get().lineNumber()).isEqualTo(18);
        assertThat(finding.get().reviewDimension()).isEqualTo("SECURITY_RULE");
    }

    @Test
    void evaluatesSupportedSensitiveKeywords() {
        assertThat(rule.evaluate(context("src/App.java", "String password = '123456';", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "String webhookSecret = \"secret\";", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "String api_key = \"key\";", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "String accessKey = \"key\";", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "String access_key = \"key\";", Map.of()))).isPresent();
    }

    @Test
    void skipsWhenSensitiveKeywordHasNoLiteralAssignment() {
        assertThat(rule.evaluate(context("src/App.java", "String token = tokenProvider.current();", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "return token;", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "String value = \"public\";", Map.of()))).isEmpty();
    }

    @Test
    void skipsWhenRuleIsDisabled() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            SensitiveLiteralRule.RULE_ID,
            new ReviewRuleSettings(SensitiveLiteralRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(context("src/App.java", "String token = \"plain-token\";", rules))).isEmpty();
    }

    @Test
    void skipsWhenFilePatternDoesNotMatch() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            SensitiveLiteralRule.RULE_ID,
            new ReviewRuleSettings(SensitiveLiteralRule.RULE_ID, "ENABLED", "*.java")
        );

        assertThat(rule.evaluate(context("docs/README.md", "String token = \"plain-token\";", rules))).isEmpty();
    }

    private ReviewRuleLineContext context(String filePath, String line, Map<String, ReviewRuleSettings> configuredRules) {
        return new ReviewRuleLineContext(filePath, 18, line, line.trim(), configuredRules);
    }
}
