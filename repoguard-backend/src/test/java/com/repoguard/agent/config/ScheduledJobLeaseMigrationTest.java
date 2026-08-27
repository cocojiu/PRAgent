package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ScheduledJobLeaseMigrationTest {

    @Test
    void createsTenantAndGlobalLeaseScopesWithExpiryIndex() throws IOException {
        String migration = Files.readString(
            Path.of("src/main/resources/db/migration/V70__scheduled_job_leases.sql"),
            StandardCharsets.UTF_8
        ).toLowerCase(Locale.ROOT);

        assertThat(migration)
            .contains("create table scheduled_job_lease")
            .contains("scope_key varchar(191) primary key")
            .contains("tenant_id bigint null")
            .contains("owner_id varchar(36) null")
            .contains("locked_until datetime(6) not null")
            .contains("foreign key (tenant_id) references tenant(id)");
    }

    @Test
    void addsMonotonicFencingTokenWithoutRewritingTheAppliedLeaseMigration() throws IOException {
        String migration = Files.readString(
            Path.of("src/main/resources/db/migration/V71__scheduled_job_lease_fencing.sql"),
            StandardCharsets.UTF_8
        ).toLowerCase(Locale.ROOT);

        assertThat(migration)
            .contains("alter table scheduled_job_lease")
            .contains("add column fencing_token bigint unsigned not null default 0")
            .contains("set fencing_token = 1")
            .contains("where owner_id is not null");
    }
}
