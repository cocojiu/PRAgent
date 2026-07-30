package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SensitiveLiteralRuleTest {

    private final SensitiveLiteralRule rule = new SensitiveLiteralRule(new RuleMatchFactory());

    @Test
    void evaluatesSensitiveLiteral() {
        var finding = rule.evaluate(context(
            "src/App.java",
            "String githubToken = \"ghp_1234567890abcdefghijklmnopqrstuvwxyz\";",
            Map.of()
        ));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-SECRET-001");
        assertThat(finding.get().filePath()).isEqualTo("src/App.java");
        assertThat(finding.get().lineNumber()).isEqualTo(18);
        assertThat(finding.get().reviewDimension()).isEqualTo("SECURITY_RULE");
    }

    @Test
    void evaluatesSupportedSensitiveKeywords() {
        String credentialValue = "CorrectHorseBatteryStaple42";
        assertThat(rule.evaluate(context(
            "src/App.java",
            "String password = '" + credentialValue + "';",
            Map.of()
        ))).isPresent();
        assertThat(rule.evaluate(context(
            "src/App.java",
            "String webhookSecret = \"whsec_1234567890abcdef\";",
            Map.of()
        ))).isPresent();
        assertThat(rule.evaluate(context(
            "src/App.java",
            "String api_key = \"sk-live-1234567890abcdef\";",
            Map.of()
        ))).isPresent();
        assertThat(rule.evaluate(context(
            "src/App.java",
            "String accessKey = \"AKIAIOSFODNN7EXAMPLE\";",
            Map.of()
        ))).isPresent();
        assertThat(rule.evaluate(context(
            "src/App.java",
            "String access_key = \"cloud-access-value-987654\";",
            Map.of()
        ))).isPresent();
    }

    @Test
    void skipsWhenSensitiveKeywordHasNoLiteralAssignment() {
        assertThat(rule.evaluate(context("src/App.java", "String token = tokenProvider.current();", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "return token;", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "String value = \"public\";", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "String token = \"${TOKEN}\";", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "String password = \"******\";", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context(
            "src/App.java",
            "static final String TOKEN_KEY = \"github.token\";",
            Map.of()
        ))).isEmpty();
    }

    @Test
    void skipsCredentialLookingFixturesInTestPaths() {
        assertThat(rule.evaluate(context(
            "src/test/java/com/example/CredentialFixture.java",
            "String token = \"ghp_1234567890abcdefghijklmnopqrstuvwxyz\";",
            Map.of()
        ))).isEmpty();
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
        return new ReviewRuleLineContext(
            filePath,
            18,
            line,
            line.trim(),
            ReviewRuleTestFixtures.configuredOrDefault(rule.id(), configuredRules)
        );
    }
}
