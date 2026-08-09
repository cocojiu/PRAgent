package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class SecretReEncryptionJobMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V59__secret_re_encryption_jobs.sql"
    );

    @Test
    void createsBoundedResumableJobAndPagedItemTables() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("create table if not exists secret_re_encryption_job")
            .contains("checkpoint_id bigint not null default 0")
            .contains("lease_until datetime(6)")
            .contains("active_slot tinyint generated always as")
            .contains("unique key uk_secret_re_encryption_active_slot")
            .contains("create table if not exists secret_re_encryption_job_item")
            .contains("unique key uk_secret_re_encryption_job_field")
            .contains("key idx_secret_re_encryption_job_item_page (job_id, id)")
            .doesNotContain("source_encryption_key")
            .doesNotContain("target_encryption_key");
    }
}
