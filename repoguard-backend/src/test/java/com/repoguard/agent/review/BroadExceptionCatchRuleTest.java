package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BroadExceptionCatchRuleTest {

    private final BroadExceptionCatchRule rule = new BroadExceptionCatchRule(new ReviewFindingFactory());

    @Test
    void evaluatesExceptionCatch() {
        var finding = rule.evaluate(context("src/App.java", "catch (Exception ex) {", Map.of()));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-JAVA-001");
        assertThat(finding.get().severity()).isEqualTo("MEDIUM");
        assertThat(finding.get().filePath()).isEqualTo("src/App.java");
        assertThat(finding.get().lineNumber()).isEqualTo(7);
    }

    @Test
    void evaluatesThrowableCatch() {
        assertThat(rule.evaluate(context("src/App.java", "catch(Throwable ex) {", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("src/App.java", "catch (Throwable ex) {", Map.of()))).isPresent();
    }

    @Test
    void skipsWhenRuleIsDisabled() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            BroadExceptionCatchRule.RULE_ID,
            new ReviewRuleSettings(BroadExceptionCatchRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(context("src/App.java", "catch (Exception ex) {", rules))).isEmpty();
    }

    @Test
    void skipsWhenFilePatternDoesNotMatch() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            BroadExceptionCatchRule.RULE_ID,
            new ReviewRuleSettings(BroadExceptionCatchRule.RULE_ID, "ENABLED", "*.java")
        );

        assertThat(rule.evaluate(context("docs/README.md", "catch (Exception ex) {", rules))).isEmpty();
    }

    private ReviewRuleLineContext context(String filePath, String line, Map<String, ReviewRuleSettings> configuredRules) {
        return new ReviewRuleLineContext(filePath, 7, line, line.trim(), configuredRules);
    }
}
