package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TenantLifecycleMigrationTest {

    @Test
    void constrainsStatusAndAddsOptimisticLifecycleMetadata() throws IOException {
        String migration = Files.readString(
            Path.of("src/main/resources/db/migration/V73__tenant_lifecycle.sql"),
            StandardCharsets.UTF_8
        ).toLowerCase(Locale.ROOT);

        assertThat(migration)
            .contains("status_version bigint unsigned not null default 1")
            .contains("status_reason varchar(512)")
            .contains("status_changed_at datetime(6)")
            .contains("check (status in ('active', 'suspended'))")
            .contains("check (status_version > 0)")
            .doesNotContain("drop table")
            .doesNotContain("delete from tenant");
    }
}
