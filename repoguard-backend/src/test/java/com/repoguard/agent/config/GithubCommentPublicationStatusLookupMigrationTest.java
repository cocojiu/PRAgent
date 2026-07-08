package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GithubCommentPublicationStatusLookupMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V44__github_comment_publication_status_lookup.sql"
    );

    @Test
    void migrationAddsGeneratedPublishedSuccessLookupAndIndexes() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql)
            .contains("published_success")
            .contains("generated always as")
            .contains("success = 1")
            .contains("github_url is not null")
            .contains("trim(github_url)")
            .contains("idx_github_comment_publication_task_finding_published")
            .contains("task_id, finding_id, published_success")
            .contains("idx_review_finding_task_category_id")
            .contains("task_id, category, id");
    }
}
