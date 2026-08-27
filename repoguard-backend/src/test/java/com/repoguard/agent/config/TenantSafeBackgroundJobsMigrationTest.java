package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TenantSafeBackgroundJobsMigrationTest {

    private static final Path V68 = Path.of(
        "src/main/resources/db/migration/V68__enterprise_tenancy.sql"
    );
    private static final Path V69 = Path.of(
        "src/main/resources/db/migration/V69__tenant_safe_background_jobs.sql"
    );

    @Test
    void addsTenantOwnershipWithoutRewritingEnterpriseTenancyMigration() throws IOException {
        String v68 = read(V68);
        String v69 = read(V69);

        assertThat(v68)
            .doesNotContain("secret_re_encryption_job")
            .doesNotContain("operational_data_cleanup_audit");
        assertThat(v69)
            .contains("alter table operational_data_cleanup_audit")
            .contains("add column tenant_id bigint null")
            .contains("alter table secret_re_encryption_job")
            .contains("add column tenant_id bigint not null default 1")
            .contains("unique key uk_secret_re_encryption_tenant_active_slot (tenant_id, active_slot)")
            .contains("alter table secret_re_encryption_job_item")
            .contains("foreign key (tenant_id, job_id)")
            .contains("references secret_re_encryption_job (tenant_id, id)");
    }

    @Test
    void runtimeInterceptionAndJobClaimBothEnforceTenant() throws IOException {
        String mybatisConfig = read(Path.of(
            "src/main/java/com/repoguard/agent/config/MybatisPlusConfig.java"
        ));
        String jobMapper = read(Path.of(
            "src/main/java/com/repoguard/agent/mapper/SecretReEncryptionJobMapper.java"
        ));

        assertThat(mybatisConfig)
            .contains("\"secret_re_encryption_job\"")
            .contains("\"secret_re_encryption_job_item\"");
        assertThat(jobMapper)
            .contains("tenant_id = #{tenantid}")
            .contains("where id = #{jobid}")
            .contains("and tenant_id = #{tenantid}");
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    }
}
