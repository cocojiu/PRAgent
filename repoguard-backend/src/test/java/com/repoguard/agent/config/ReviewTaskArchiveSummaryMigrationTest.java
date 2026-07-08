package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReviewTaskArchiveSummaryMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V49__review_task_archive_summary.sql"
    );

    @Test
    void migrationCreatesArchiveSummaryWithLookupIndexes() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql)
            .contains("create table if not exists review_task_archive_summary")
            .contains("task_id bigint not null")
            .contains("cleanup_batch_id bigint not null")
            .contains("backup_reference varchar(128) not null")
            .contains("archived_at datetime not null")
            .contains("unique key uk_review_task_archive_summary_task (task_id)")
            .contains("idx_review_task_archive_summary_repo_created")
            .contains("idx_review_task_archive_summary_created")
            .contains("idx_review_task_archive_summary_batch");
        assertThat(sql).doesNotContain("delete from", "truncate", "drop table");
    }
}
