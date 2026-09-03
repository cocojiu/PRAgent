package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepositoryPolicyParserTest {

    private final RepositoryPolicyParser parser = new RepositoryPolicyParser(Set.of("RG-AUTH-001"));

    @Test
    void parsesOnlyTheVersionedSafePolicyShape() {
        RepositoryPolicyDocument document = parser.parse("""
            schemaVersion: 1
            include:
              - "src/**/*.java"
            exclude:
              - "vendor/**"
            rules:
              RG-AUTH-001:
                enabled: true
                severity: HIGH
                enforcement: BLOCK
            llm:
              enabled: false
              tokenBudget: 12000
              costBudget: 2.50
            publication:
              commentMode: SUMMARY
              checkMode: NEUTRAL
            suppressions:
              - ruleId: RG-AUTH-001
                fileGlob: "src/test/**"
                reason: "legacy fixture"
                expiresAt: "2099-12-31T00:00:00Z"
            """);

        assertThat(document.schemaVersion()).isEqualTo(1);
        assertThat(document.includePatterns()).containsExactly("src/**/*.java");
        assertThat(document.rules()).containsKey("RG-AUTH-001");
        assertThat(document.rules().get("RG-AUTH-001").enforcementMode()).isEqualTo(EnforcementMode.BLOCK);
        assertThat(document.llm().tokenBudget()).isEqualTo(12000);
        assertThat(document.suppressions()).hasSize(1);
    }

    @Test
    void rejectsUnknownFieldsAndProviderSecrets() {
        assertThatThrownBy(() -> parser.parse("""
            schemaVersion: 1
            providerUrl: https://example.invalid
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown field");
    }

    @Test
    void rejectsUnknownRulesAndOverBroadSuppressions() {
        assertThatThrownBy(() -> parser.parse("""
            schemaVersion: 1
            rules:
              RG-UNKNOWN:
                enabled: false
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown rule");
        assertThatThrownBy(() -> parser.parse("""
            schemaVersion: 1
            suppressions:
              - ruleId: RG-AUTH-001
                fileGlob: "**"
                reason: "too broad"
                expiresAt: "2099-12-31T00:00:00Z"
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("too broad");
    }

    @Test
    void supportsAliasesAndFailsClosedForMalformedOrUnboundedValues() {
        assertThat(parser.parseOptional("  ")).isEmpty();
        RepositoryPolicyDocument aliases = parser.parse("""
            schemaVersion: 2
            includePatterns: ["src/**"]
            excludePatterns: ["src/generated/**"]
            rules:
              RG-AUTH-001:
                enabled: false
                severity: low
                enforcementMode: observe
            llm:
              enabled: false
              maxTokens: 1024
              maxCost: 1.25
            publication:
              commentMode: inline
              checkMode: blocking
            suppressions:
              - ruleId: RG-AUTH-001
                symbol: LegacyToken
                reason: "fixture"
                expiresAt: "2099-12-31T00:00:00"
            """);
        assertThat(aliases.schemaVersion()).isEqualTo(2);
        assertThat(aliases.excludePatterns()).containsExactly("src/generated/**");
        assertThat(aliases.rules().get("RG-AUTH-001").enforcementMode()).isEqualTo(EnforcementMode.OBSERVE);
        assertThat(aliases.llm().tokenBudget()).isEqualTo(1024);
        assertThat(aliases.publication().checkMode()).isEqualTo("BLOCKING");
        assertThat(aliases.suppressions()).singleElement().satisfies(value ->
            assertThat(value.symbol()).isEqualTo("LegacyToken"));

        assertThatThrownBy(() -> parser.parse("schemaVersion: 0"))
            .hasMessageContaining("between 1 and 10");
        assertThatThrownBy(() -> parser.parse("include: not-an-array"))
            .hasMessageContaining("must be a bounded array");
        assertThatThrownBy(() -> parser.parse("include: ['/etc/passwd']"))
            .hasMessageContaining("unsafe path");
        assertThatThrownBy(() -> parser.parse("rules: []"))
            .hasMessageContaining("rules must be an object");
        assertThatThrownBy(() -> parser.parse("llm: {enabled: 1}"))
            .hasMessageContaining("llm.enabled must be boolean");
        assertThatThrownBy(() -> parser.parse("llm: {tokenBudget: 0}"))
            .hasMessageContaining("between 1 and 128000");
        assertThatThrownBy(() -> parser.parse("llm: {costBudget: -1}"))
            .hasMessageContaining("greater than 0");
        assertThatThrownBy(() -> parser.parse("publication: {commentMode: never}"))
            .hasMessageContaining("unsupported value");
        assertThatThrownBy(() -> parser.parse("suppressions: [{ruleId: RG-AUTH-001, reason: x, expiresAt: 2099-01-01T00:00:00}]"))
            .hasMessageContaining("fileGlob or symbol");
        assertThatThrownBy(() -> parser.parse("suppressions: [{ruleId: RG-AUTH-001, fileGlob: src/**, reason: x, expiresAt: bad}]"))
            .hasMessageContaining("ISO-8601");
    }
}
