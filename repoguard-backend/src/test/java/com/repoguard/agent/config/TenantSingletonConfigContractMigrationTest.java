package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TenantSingletonConfigContractMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V78__tenant_singleton_config_contract.sql"
    );

    @Test
    void migrationGuardsMissingAndDuplicateRowsBeforeAddingTenantUniqueKeys() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        assertThat(sql).contains(
            "create table flyway_v78_tenant_singleton_config_violation",
            "review_policy_config.duplicate_tenant",
            "review_policy_config.missing_tenant",
            "system_settings_config.duplicate_tenant",
            "system_settings_config.missing_tenant",
            "check (violation_count = 0)",
            "add unique key uk_review_policy_config_tenant (tenant_id)",
            "add unique key uk_system_settings_config_tenant (tenant_id)"
        );
        assertThat(sql).doesNotContain(
            "delete from review_policy_config",
            "delete from system_settings_config",
            "update review_policy_config",
            "update system_settings_config"
        );
    }
}
