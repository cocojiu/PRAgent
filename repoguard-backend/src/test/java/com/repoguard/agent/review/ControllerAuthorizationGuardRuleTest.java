package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControllerAuthorizationGuardRuleTest {

    private final ControllerAuthorizationGuardRule rule = new ControllerAuthorizationGuardRule(new ReviewFindingFactory());

    @Test
    void detectsMutatingControllerMappingWithoutAuthorizationGuard() {
        var finding = rule.evaluate(context(
            "src/main/java/com/example/AdminController.java",
            "@DeleteMapping(\"/users/{id}\")",
            Map.of(),
            false
        ));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo(ControllerAuthorizationGuardRule.RULE_ID);
        assertThat(finding.get().severity()).isEqualTo("HIGH");
        assertThat(finding.get().lineNumber()).isEqualTo(27);
    }

    @Test
    void skipsMutatingControllerMappingWhenPatchHasAuthorizationGuard() {
        var finding = rule.evaluate(context(
            "src/main/java/com/example/AdminController.java",
            "@PostMapping(\"/users\")",
            Map.of(),
            true
        ));

        assertThat(finding).isEmpty();
    }

    @Test
    void skipsDisabledRule() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            ControllerAuthorizationGuardRule.RULE_ID,
            new ReviewRuleSettings(ControllerAuthorizationGuardRule.RULE_ID, "DISABLED", "")
        );

        var finding = rule.evaluate(context(
            "src/main/java/com/example/AdminController.java",
            "@PatchMapping(\"/users/{id}\")",
            rules,
            false
        ));

        assertThat(finding).isEmpty();
    }

    @Test
    void skipsNonControllerFile() {
        var finding = rule.evaluate(context(
            "src/main/java/com/example/AdminService.java",
            "@PutMapping(\"/users/{id}\")",
            Map.of(),
            false
        ));

        assertThat(finding).isEmpty();
    }

    @Test
    void skipsReadOnlyControllerMapping() {
        var finding = rule.evaluate(context(
            "src/main/java/com/example/AdminController.java",
            "@GetMapping(\"/users/{id}\")",
            Map.of(),
            false
        ));

        assertThat(finding).isEmpty();
    }

    private ReviewRuleLineContext context(
        String filePath,
        String line,
        Map<String, ReviewRuleSettings> configuredRules,
        boolean patchHasAuthorizationGuard
    ) {
        return new ReviewRuleLineContext(filePath, 27, line, line.trim(), configuredRules, patchHasAuthorizationGuard);
    }
}
