package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeclarativeRuleMatcherTest {

    private final DeclarativeRuleMatcher matcher = new DeclarativeRuleMatcher(new DeclarativeRulePolicy());

    @Test
    void matchesRegexOnlyOnConfiguredFileAndReturnsEvidence() {
        ReviewRuleSettings settings = settings("REGEX", "password\\s*=", "**/generated/**");

        assertThat(matcher.matches(settings, "src/main/App.java", 12, "String password = value;"))
            .singleElement()
            .satisfies(match -> {
                assertThat(match.ruleId()).isEqualTo("RG-CUSTOM-001");
                assertThat(match.lineNumber()).isEqualTo(12);
                assertThat(match.evidence()).contains("password");
                assertThat(match.evidenceVerified()).isTrue();
            });
        assertThat(matcher.matches(settings, "src/generated/App.java", 12, "password = value;"))
            .isEmpty();
    }

    @Test
    void restrictedAstSupportsTokenAndCallQueries() {
        assertThat(matcher.matches(settings("AST", "token:execute", ""), "Job.java", 1, "execute(job);"))
            .hasSize(1);
        assertThat(matcher.matches(settings("AST", "call:execute", ""), "Job.java", 1, "executor.execute(job);"))
            .hasSize(1);
        assertThat(matcher.matches(settings("AST", "call:execute", ""), "Job.java", 1, "executed = true;"))
            .isEmpty();
    }

    @Test
    void unsafeRegexIsRejectedBeforeExecution() {
        DeclarativeRulePolicy policy = new DeclarativeRulePolicy();

        assertThatThrownBy(() -> policy.validate("REGEX", "(a+)+", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("backtracking");
        assertThatThrownBy(() -> policy.validate("AST", "raw:execute", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("token:<name>");
    }

    @Test
    void policyNormalizesTypesAndRejectsUnsafeOrInvalidDefinitions() {
        DeclarativeRulePolicy policy = new DeclarativeRulePolicy();
        assertThat(policy.normalizeType(null)).isEqualTo(DeclarativeRulePolicy.BUILTIN);
        assertThat(policy.detectorVersion("BUILTIN")).isEqualTo("builtin-detector-v2");
        assertThat(policy.detectorVersion("REGEX")).isEqualTo("declarative-regex-v1");
        assertThat(policy.detectorVersion("AST")).isEqualTo("declarative-ast-v1");
        assertThatThrownBy(() -> policy.normalizeType("sql"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("BUILTIN", "token:x", ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("REGEX", "[", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid");
        assertThatThrownBy(() -> policy.validate("REGEX", "x(?=y)", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lookaround");
        assertThatThrownBy(() -> policy.validate("REGEX", "", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("required");
        assertThatThrownBy(() -> policy.validate("REGEX", "bad\u0001pattern", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("control characters");
        assertThatThrownBy(() -> policy.validate("REGEX", "x", "bad\u0001pattern"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private ReviewRuleSettings settings(String detectorType, String expression, String exceptions) {
        return new ReviewRuleSettings(
            "RG-CUSTOM-001",
            "ENABLED",
            "*.java",
            "HIGH",
            95,
            EnforcementMode.COMMENT,
            "Replace with a safe API",
            "Generated source is exempt",
            "Custom declarative rule matched",
            "declarative-" + detectorType.toLowerCase() + "-v1",
            1,
            1,
            detectorType,
            expression,
            exceptions
        );
    }
}
