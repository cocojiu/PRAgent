package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.mapper.DashboardMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
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
    void dashboardIndexAlignmentsKeepExpectedLeadingColumns() throws IOException {
        String migrations = migrationSql();

        for (DashboardSqlVerificationPlan.IndexAlignment alignment : plan.indexAlignments()) {
            assertThat(migrations)
                .as(alignment.indexName() + " leading columns")
                .contains(indexDefinition(alignment.indexName(), alignment.leadingColumns()));
            assertThat(alignment.reason())
                .as(alignment.indexName() + " alignment reason")
                .isNotBlank();
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

    @Test
    void explainObservationsCoverEveryDashboardQueryAndReferenceKnownIndexes() throws IOException {
        Set<String> mapperMethods = plan.queryAssumptions().stream()
            .map(DashboardSqlVerificationPlan.QueryAssumption::mapperMethod)
            .collect(Collectors.toSet());
        String migrations = migrationSql();

        assertThat(plan.explainObservations())
            .extracting(DashboardSqlVerificationPlan.ExplainObservation::mapperMethod)
            .containsExactlyInAnyOrderElementsOf(mapperMethods);

        for (DashboardSqlVerificationPlan.ExplainObservation observation : plan.explainObservations()) {
            assertThat(observation.keyCandidates())
                .as(observation.mapperMethod() + " EXPLAIN key candidates")
                .isNotEmpty();
            assertThat(observation.acceptableAccessTypes())
                .as(observation.mapperMethod() + " acceptable access types")
                .allMatch(accessType -> List.of("range", "ref", "eq_ref", "const").contains(accessType));
            assertThat(observation.rowsExpectation())
                .as(observation.mapperMethod() + " rows expectation")
                .containsIgnoringCase("rows");
            assertThat(observation.extraWatchItems())
                .as(observation.mapperMethod() + " extra watch items")
                .isNotEmpty();

            for (String keyCandidate : observation.keyCandidates()) {
                assertThat(migrations)
                    .as(observation.mapperMethod() + " EXPLAIN key candidate " + keyCandidate)
                    .contains(keyCandidate.toLowerCase(Locale.ROOT));
            }
        }
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

    private String indexDefinition(String indexName, List<String> columns) {
        return "add key " + indexName.toLowerCase(Locale.ROOT)
            + " (" + String.join(", ", columns).toLowerCase(Locale.ROOT) + ")";
    }
}
