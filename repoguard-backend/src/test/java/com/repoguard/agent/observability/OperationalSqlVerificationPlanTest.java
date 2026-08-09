package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class OperationalSqlVerificationPlanTest {

    private final OperationalSqlVerificationPlan plan = new OperationalSqlVerificationPlan();

    @Test
    void everyOperationalQueryAssumptionReferencesExistingMapperMethodAndSqlAnnotation() throws Exception {
        for (OperationalSqlVerificationPlan.QueryAssumption assumption : plan.queryAssumptions()) {
            Method method = mapperMethod(assumption);

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
    void everyOperationalQueryAssumptionReferencesIndexesDeclaredByMigrations() throws IOException {
        String migrations = migrationSql();

        for (OperationalSqlVerificationPlan.QueryAssumption assumption : plan.queryAssumptions()) {
            for (String index : assumption.supportingIndexes()) {
                assertThat(migrations)
                    .as(assumption.mapperMethod() + " supporting index " + index)
                    .contains(index.toLowerCase(Locale.ROOT));
            }
        }
    }

    @Test
    void operationalIndexAlignmentsKeepExpectedLeadingColumns() throws IOException {
        String migrations = migrationSql();

        for (OperationalSqlVerificationPlan.IndexAlignment alignment : plan.indexAlignments()) {
            assertThat(migrations)
                .as(alignment.indexName() + " leading columns")
                .contains(indexDefinition(alignment.indexName(), alignment.leadingColumns()));
            assertThat(alignment.reason())
                .as(alignment.indexName() + " alignment reason")
                .isNotBlank();
        }
    }

    @Test
    void planCoversOperationalHotQueriesOutsideDashboardAggregates() {
        assertThat(plan.queryAssumptions())
            .extracting(OperationalSqlVerificationPlan.QueryAssumption::mapperMethod)
            .containsExactlyInAnyOrder(
                "selectReviewRuleHitCounts",
                "selectReviewRuleFeedbackStat",
                "selectFindingSeverityCounts",
                "selectReviewTaskDetailSummary",
                "selectGithubCommentPreviewCommentableFindings",
                "selectGithubCommentPublishCandidatesAfterId",
                "selectChangedFilesWithFindings",
                "selectChangedFilesWithoutFindings",
                "selectMessageQueueExceptionTasks"
            );
    }

    @Test
    void explainObservationsCoverEveryOperationalQueryAndReferenceKnownIndexes() throws IOException {
        Set<String> mapperMethods = plan.queryAssumptions().stream()
            .map(OperationalSqlVerificationPlan.QueryAssumption::mapperMethod)
            .collect(Collectors.toSet());
        String migrations = migrationSql();

        assertThat(plan.explainObservations())
            .extracting(OperationalSqlVerificationPlan.ExplainObservation::mapperMethod)
            .containsExactlyInAnyOrderElementsOf(mapperMethods);

        for (OperationalSqlVerificationPlan.ExplainObservation observation : plan.explainObservations()) {
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
            .map(OperationalSqlVerificationPlan.ExplainObservation::mapperMethod)
            .collect(Collectors.toSet());
        Map<String, OperationalSqlVerificationPlan.QueryAssumption> assumptions = plan.queryAssumptions().stream()
            .collect(Collectors.toMap(
                OperationalSqlVerificationPlan.QueryAssumption::mapperMethod,
                Function.identity()
            ));
        Map<String, OperationalSqlVerificationPlan.ExplainObservation> observations = plan.explainObservations().stream()
            .collect(Collectors.toMap(
                OperationalSqlVerificationPlan.ExplainObservation::mapperMethod,
                Function.identity()
            ));
        String migrations = migrationSql();

        assertThat(plan.explainTableExpectations())
            .extracting(OperationalSqlVerificationPlan.ExplainTableExpectation::mapperMethod)
            .containsAll(mapperMethods);

        for (OperationalSqlVerificationPlan.ExplainTableExpectation expectation : plan.explainTableExpectations()) {
            OperationalSqlVerificationPlan.ExplainObservation observation = observations.get(expectation.mapperMethod());
            String mapperSql = mapperSql(assumptions.get(expectation.mapperMethod())).toLowerCase(Locale.ROOT);

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
    void explainTableExpectationsCoverEveryCorrelatedSubqueryTable() {
        assertThat(plan.explainTableExpectations().stream()
            .filter(expectation -> "github_comment_publication".equals(expectation.tableName()))
            .map(OperationalSqlVerificationPlan.ExplainTableExpectation::mapperMethod)
            .collect(Collectors.toSet()))
            .containsExactlyInAnyOrder(
                "selectGithubCommentPreviewCommentableFindings",
                "selectGithubCommentPublishCandidatesAfterId"
            );
        assertThat(plan.explainTableExpectations().stream()
            .filter(expectation -> "changed_file".equals(expectation.tableName()))
            .map(OperationalSqlVerificationPlan.ExplainTableExpectation::mapperMethod)
            .collect(Collectors.toSet()))
            .containsExactlyInAnyOrder(
                "selectReviewTaskDetailSummary",
                "selectChangedFilesWithFindings",
                "selectChangedFilesWithoutFindings"
            );
    }

    private Method mapperMethod(OperationalSqlVerificationPlan.QueryAssumption assumption) throws NoSuchMethodException {
        Class<?>[] parameterTypes = assumption.parameterTypes().toArray(Class<?>[]::new);
        return assumption.mapperClass().getMethod(assumption.mapperMethod(), parameterTypes);
    }

    private String mapperSql(OperationalSqlVerificationPlan.QueryAssumption assumption) throws NoSuchMethodException {
        return String.join("\n", mapperMethod(assumption).getAnnotation(Select.class).value())
            .replace("<script>", "")
            .replace("</script>", "");
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
