package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequiredColumnWithoutDefaultRuleTest {

    private final RequiredColumnWithoutDefaultRule rule = new RequiredColumnWithoutDefaultRule(new RuleMatchFactory());

    @Test
    void evaluatesRequiredColumnWithoutDefault() {
        var finding = rule.evaluate(context(
            "src/main/resources/db/migration/V32__add_required_column.sql",
            "alter table review_task add column reviewer_id bigint not null;",
            Map.of()
        ));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-DB-003");
        assertThat(finding.get().filePath()).isEqualTo("src/main/resources/db/migration/V32__add_required_column.sql");
        assertThat(finding.get().lineNumber()).isEqualTo(9);
        assertThat(finding.get().reviewDimension()).isEqualTo("DATABASE_COMPATIBILITY_RULE");
    }

    @Test
    void evaluatesAddWithoutColumnKeyword() {
        assertThat(rule.evaluate(context("db/V1.sql", "alter table review_task add reviewer_id bigint not null;", Map.of())))
            .isPresent();
    }

    @Test
    void skipsWhenMigrationIsCompatibleOrNotSql() {
        assertThat(rule.evaluate(context(
            "db/V1.sql",
            "alter table review_task add column review_source varchar(32) not null default 'manual';",
            Map.of()
        ))).isEmpty();
        assertThat(rule.evaluate(context("db/V1.sql", "alter table review_task add column reviewer_id bigint;", Map.of())))
            .isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "alter table review_task add column reviewer_id bigint not null;", Map.of())))
            .isEmpty();
        assertThat(rule.evaluate(context(
            "db/V1.sql",
            "-- alter table review_task add column reviewer_id bigint not null;",
            Map.of()
        ))).isEmpty();
    }

    @Test
    void readsMultilineStatementBeforeDecidingDefaultCompatibility() {
        String patch = """
            @@ -1,0 +1,3 @@
            +alter table review_task
            +  add column source varchar(32) not null
            +  default 'manual';
            """;
        var finding = rule.evaluate(context(
            "src/main/resources/db/migration/V2__source.sql",
            "add column source varchar(32) not null",
            Map.of(),
            ChangedFileContext.notRequested("src/main/resources/db/migration/V2__source.sql"),
            patch
        ));

        assertThat(finding).isEmpty();
    }

    @Test
    void marksRequiredColumnCandidateUnverifiedWhenMigrationContextIsUnavailable() {
        var finding = rule.evaluate(context(
            "src/main/resources/db/migration/V3__tenant.sql",
            "alter table review_task add column tenant_id bigint not null;",
            Map.of(),
            ChangedFileContext.status(
                "src/main/resources/db/migration/V3__tenant.sql",
                "head",
                ChangedFileContext.Status.UNAVAILABLE,
                "fetch_failed"
            ),
            "@@ -1,0 +1,1 @@\n+alter table review_task add column tenant_id bigint not null;"
        ));

        assertThat(finding).isPresent();
        assertThat(finding.get().evidenceVerified()).isFalse();
    }

    @Test
    void skipsWhenRuleIsDisabled() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            RequiredColumnWithoutDefaultRule.RULE_ID,
            new ReviewRuleSettings(RequiredColumnWithoutDefaultRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(context(
            "db/V1.sql",
            "alter table review_task add column reviewer_id bigint not null;",
            rules
        ))).isEmpty();
    }

    @Test
    void skipsWhenFilePatternDoesNotMatch() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            RequiredColumnWithoutDefaultRule.RULE_ID,
            new ReviewRuleSettings(RequiredColumnWithoutDefaultRule.RULE_ID, "ENABLED", "src/main/resources/db/migration/*.sql")
        );

        assertThat(rule.evaluate(context(
            "docs/example.sql",
            "alter table review_task add column reviewer_id bigint not null;",
            rules
        ))).isEmpty();
    }

    private ReviewRuleLineContext context(String filePath, String line, Map<String, ReviewRuleSettings> configuredRules) {
        return context(
            filePath,
            line,
            configuredRules,
            ChangedFileContext.notRequested(filePath),
            line
        );
    }

    private ReviewRuleLineContext context(
        String filePath,
        String line,
        Map<String, ReviewRuleSettings> configuredRules,
        ChangedFileContext changedFileContext,
        String patch
    ) {
        return new ReviewRuleLineContext(
            filePath,
            9,
            line,
            line.trim(),
            ReviewRuleTestFixtures.configuredOrDefault(rule.id(), configuredRules),
            false,
            changedFileContext,
            patch
        );
    }
}
