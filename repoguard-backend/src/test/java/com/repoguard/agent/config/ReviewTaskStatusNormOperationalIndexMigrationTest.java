package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ReviewTaskStatusNormOperationalIndexMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V52__review_task_status_norm_operational_indexes.sql"
    );

    @Test
    void migrationAddsStatusNormCreatedAtIndexForOperationalQueues() throws IOException {
        String sql = Files.readString(MIGRATION).toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("review_task")
            .contains("idx_review_task_status_norm_created")
            .contains("status_norm, created_at");
    }
}
