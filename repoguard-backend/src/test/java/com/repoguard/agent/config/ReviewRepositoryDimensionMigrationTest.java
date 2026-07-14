package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReviewRepositoryDimensionMigrationTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V48__review_repository_dimension.sql"
    );

    @Test
    void migrationCreatesRepositoryDimensionAndBackfillsFromReviewTask() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql)
            .contains("create table if not exists review_repository_dimension")
            .contains("unique key uk_review_repository_dimension_org_repo (organization, repository)")
            .contains("idx_review_repository_dimension_label")
            .contains("insert into review_repository_dimension")
            .contains("from review_task")
            .contains("concat(trim(organization), '/', trim(repository))")
            .contains("group by trim(organization), trim(repository)\non duplicate key update")
            .contains("on duplicate key update");
        assertThat(sql).doesNotContain(
            "group by trim(organization), trim(repository), concat(trim(organization), '/', trim(repository))"
        );
        assertThat(sql).doesNotContain("delete from review_task", "truncate", "drop table");
    }
}
