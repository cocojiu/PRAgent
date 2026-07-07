package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.mapper.DashboardMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class DashboardSqlVerificationPlanTest {

    private final DashboardSqlVerificationPlan plan = new DashboardSqlVerificationPlan();

    @Test
    void everyDashboardQueryAssumptionReferencesExistingMapperMethodAndSqlAnnotation() throws Exception {
        for (DashboardSqlVerificationPlan.QueryAssumption assumption : plan.queryAssumptions()) {
            Method method = mapperMethod(assumption.mapperMethod());

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
                "selectLatestReviewTaskDate",
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

    private Method mapperMethod(String methodName) throws NoSuchMethodException {
        if ("selectLatestReviewTaskDate".equals(methodName)) {
            return DashboardMapper.class.getMethod(methodName);
        }
        return DashboardMapper.class.getMethod(methodName, LocalDate.class);
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

    @Test
    void explainTableExpectationsPinKeyTablesAliasesAndIndexes() throws Exception {
        Set<String> mapperMethods = plan.explainObservations().stream()
            .map(DashboardSqlVerificationPlan.ExplainObservation::mapperMethod)
            .collect(Collectors.toSet());
        Map<String, DashboardSqlVerificationPlan.ExplainObservation> observations = plan.explainObservations().stream()
            .collect(Collectors.toMap(
                DashboardSqlVerificationPlan.ExplainObservation::mapperMethod,
                Function.identity()
            ));
        String migrations = migrationSql();

        assertThat(plan.explainTableExpectations())
            .extracting(DashboardSqlVerificationPlan.ExplainTableExpectation::mapperMethod)
            .containsAll(mapperMethods);

        for (DashboardSqlVerificationPlan.ExplainTableExpectation expectation : plan.explainTableExpectations()) {
            DashboardSqlVerificationPlan.ExplainObservation observation = observations.get(expectation.mapperMethod());
            String mapperSql = mapperSql(expectation.mapperMethod()).toLowerCase(Locale.ROOT);

            assertThat(observation)
                .as(expectation.mapperMethod() + " table expectation must reference a known EXPLAIN observation")
                .isNotNull();
            assertThat(mapperSql)
                .as(expectation.mapperMethod() + " must reference table " + expectation.tableName())
                .contains(expectation.tableName());
            if (!expectation.tableAlias().isBlank()) {
                assertThat(mapperSql)
                    .as(expectation.mapperMethod() + " must reference alias " + expectation.tableAlias())
                    .contains(expectation.tableName() + " " + expectation.tableAlias());
            }
            assertThat(expectation.keyCandidates())
                .as(expectation.mapperMethod() + " " + expectation.tableName() + " key candidates")
                .isNotEmpty()
                .allSatisfy(keyCandidate -> {
                    assertThat(observation.keyCandidates())
                        .as(expectation.mapperMethod() + " observation should include " + keyCandidate)
                        .contains(keyCandidate);
                    assertThat(migrations)
                        .as(expectation.mapperMethod() + " table key candidate " + keyCandidate)
                        .contains(keyCandidate.toLowerCase(Locale.ROOT));
                });
            assertThat(expectation.acceptableAccessTypes())
                .as(expectation.mapperMethod() + " " + expectation.tableName() + " access types")
                .isNotEmpty()
                .allMatch(accessType -> List.of("range", "ref", "eq_ref", "const").contains(accessType));
            assertThat(expectation.rowsExpectation())
                .as(expectation.mapperMethod() + " " + expectation.tableName() + " rows expectation")
                .containsIgnoringCase("rows");
            assertThat(expectation.extraWatchItems())
                .as(expectation.mapperMethod() + " " + expectation.tableName() + " watch items")
                .isNotEmpty();
        }
    }

    @Test
    void explainTableExpectationsCoverEveryJoinedFindingQuery() {
        assertThat(plan.explainTableExpectations().stream()
            .filter(expectation -> "review_finding".equals(expectation.tableName()))
            .map(DashboardSqlVerificationPlan.ExplainTableExpectation::mapperMethod)
            .collect(Collectors.toSet()))
            .containsExactlyInAnyOrder(
                "selectRuleHitCounts",
                "selectRecentHighRiskReviews",
                "selectLlmQualityByModelStats",
                "selectLlmQualityByRepositoryStats"
            );
    }

    private String mapperSql(String methodName) throws NoSuchMethodException {
        return String.join("\n", mapperMethod(methodName).getAnnotation(Select.class).value());
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
