package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.mapper.DashboardMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class DashboardSqlVerificationPlanTest {

    private final DashboardSqlVerificationPlan plan = new DashboardSqlVerificationPlan();

    @Test
    void everyDashboardQueryAssumptionReferencesExistingMapperMethodAndSqlAnnotation() throws Exception {
        for (DashboardSqlVerificationPlan.QueryAssumption assumption : plan.queryAssumptions()) {
            Method method = DashboardMapper.class.getMethod(assumption.mapperMethod(), LocalDate.class);

            assertThat(method.getAnnotation(Select.class))
                .as(assumption.mapperMethod() + " must keep @Select SQL contract")
                .isNotNull();
            assertThat(assumption.verificationScope())
                .as(assumption.mapperMethod() + " verification scope")
                .isNotBlank();
            assertThat(assumption.supportingIndexes())
                .as(assumption.mapperMethod() + " supporting indexes")
                .isNotEmpty();
        }
    }

    @Test
    void everyDashboardQueryAssumptionReferencesIndexesDeclaredByMigrations() throws IOException {
        String migrations = migrationSql();

        for (DashboardSqlVerificationPlan.QueryAssumption assumption : plan.queryAssumptions()) {
            for (String index : assumption.supportingIndexes()) {
                assertThat(migrations)
                    .as(assumption.mapperMethod() + " supporting index " + index)
                    .contains(index.toLowerCase(Locale.ROOT));
            }
        }
    }

    @Test
    void planCoversEveryDashboardMapperAggregateQuery() {
        assertThat(plan.queryAssumptions())
            .extracting(DashboardSqlVerificationPlan.QueryAssumption::mapperMethod)
            .containsExactlyInAnyOrder(
                "selectMetricStat",
                "selectRiskLevelCounts",
                "selectReviewTrendCounts",
                "selectRuleHitCounts",
                "selectRecentHighRiskReviews",
                "selectLlmQualityTrendCounts",
                "selectLlmQualityByModelStats",
                "selectLlmQualityByRepositoryStats"
            );
    }

    private String migrationSql() throws IOException {
        Path migrationDir = Path.of("src/main/resources/db/migration");
        try (var files = Files.walk(migrationDir)) {
            return String.join("\n", files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted()
                .map(this::readString)
                .toList()).toLowerCase(Locale.ROOT);
        }
    }

    private String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read migration " + path, ex);
        }
    }
}
