package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DestructiveMigrationRuleTest {

    private final DestructiveMigrationRule rule = new DestructiveMigrationRule(new ReviewFindingFactory());

    @Test
    void evaluatesDropTableMigration() {
        var finding = rule.evaluate(context(
            "src/main/resources/db/migration/V31__drop_legacy_table.sql",
            "drop table legacy_review_task;",
            Map.of()
        ));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-DB-002");
        assertThat(finding.get().severity()).isEqualTo("HIGH");
        assertThat(finding.get().filePath()).isEqualTo("src/main/resources/db/migration/V31__drop_legacy_table.sql");
        assertThat(finding.get().lineNumber()).isEqualTo(3);
        assertThat(finding.get().reviewDimension()).isEqualTo("DATABASE_COMPATIBILITY_RULE");
    }

    @Test
    void evaluatesSupportedDestructiveDdl() {
        assertThat(rule.evaluate(context("db/V1.sql", "alter table review_task drop column legacy_id;", Map.of()))).isPresent();
        assertThat(rule.evaluate(context("db/V1.sql", "truncate table review_task;", Map.of()))).isPresent();
    }

    @Test
    void skipsWhenFileIsNotSqlOrDdlIsNotDestructive() {
        assertThat(rule.evaluate(context("src/App.java", "drop table legacy_review_task;", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("db/V1.sql", "create table review_task(id bigint);", Map.of()))).isEmpty();
    }

    @Test
    void skipsWhenRuleIsDisabled() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            DestructiveMigrationRule.RULE_ID,
            new ReviewRuleSettings(DestructiveMigrationRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(context("db/V1.sql", "drop table legacy_review_task;", rules))).isEmpty();
    }

    @Test
    void skipsWhenFilePatternDoesNotMatch() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            DestructiveMigrationRule.RULE_ID,
            new ReviewRuleSettings(DestructiveMigrationRule.RULE_ID, "ENABLED", "src/main/resources/db/migration/*.sql")
        );

        assertThat(rule.evaluate(context("docs/example.sql", "drop table legacy_review_task;", rules))).isEmpty();
    }

    private ReviewRuleLineContext context(String filePath, String line, Map<String, ReviewRuleSettings> configuredRules) {
        return new ReviewRuleLineContext(filePath, 3, line, line.trim(), configuredRules);
    }
}
