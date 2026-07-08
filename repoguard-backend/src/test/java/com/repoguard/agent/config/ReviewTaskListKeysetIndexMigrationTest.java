package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReviewTaskListKeysetIndexMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V47__review_task_list_keyset_indexes.sql"
    );

    @Test
    void migrationAddsBoundedCompositeIndexesForReviewTaskListKeysetQueries() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql)
            .contains("idx_review_task_list_created_id")
            .contains("created_at, id")
            .contains("idx_review_task_list_status_created_id")
            .contains("status, created_at, id")
            .contains("idx_review_task_list_repo_status_created_id")
            .contains("repository, status, created_at, id")
            .contains("idx_review_task_list_trigger_created_id")
            .contains("trigger_source, created_at, id");
        assertThat(countOccurrences(sql, "add key idx_review_task_list_")).isEqualTo(4);
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
