package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TenantQuotaMigrationTest {

    @Test
    void createsPerTenantQuotaAndDailyUsageTables() throws IOException {
        String migration = Files.readString(
            Path.of("src/main/resources/db/migration/V74__tenant_review_quotas.sql"),
            StandardCharsets.UTF_8
        ).toLowerCase(Locale.ROOT);

        assertThat(migration)
            .contains("create table tenant_quota_config")
            .contains("tenant_id bigint not null")
            .contains("max_daily_reviews int unsigned not null default 1000")
            .contains("create table tenant_quota_usage")
            .contains("primary key (tenant_id, usage_date)")
            .contains("insert into tenant_quota_config")
            .doesNotContain("drop table")
            .doesNotContain("delete from tenant");
    }
}
