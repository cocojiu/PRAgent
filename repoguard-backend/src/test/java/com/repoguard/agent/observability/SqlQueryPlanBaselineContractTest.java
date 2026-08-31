package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class SqlQueryPlanBaselineContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, QueryBinding> BINDINGS = Map.of(
        "ChangedFileMapper.selectTopChangedFilesByChurn",
        new QueryBinding(ChangedFileMapper.class, "selectTopChangedFilesByChurn", Long.class, int.class),
        "ChangedFileMapper.selectChangedFilesWithFindings",
        new QueryBinding(ChangedFileMapper.class, "selectChangedFilesWithFindings", Page.class, Long.class),
        "ChangedFileMapper.selectChangedFilesWithoutFindings",
        new QueryBinding(ChangedFileMapper.class, "selectChangedFilesWithoutFindings", Page.class, Long.class),
        "ReviewFindingMapper.selectGithubCommentPreviewFindingStat",
        new QueryBinding(ReviewFindingMapper.class, "selectGithubCommentPreviewFindingStat", Long.class),
        "ReviewFindingMapper.selectGithubCommentPreviewFindings",
        new QueryBinding(ReviewFindingMapper.class, "selectGithubCommentPreviewFindings", Long.class, long.class, int.class),
        "ReviewFindingMapper.selectGithubCommentPreviewCommentableFindings",
        new QueryBinding(ReviewFindingMapper.class, "selectGithubCommentPreviewCommentableFindings", Long.class, long.class, int.class),
        "ReviewFindingMapper.selectGithubCommentPublishCandidatesAfterId",
        new QueryBinding(ReviewFindingMapper.class, "selectGithubCommentPublishCandidatesAfterId", Long.class, long.class, int.class)
    );

    @Test
    void baselineIsVersionedSizedAndDisablesUnsupportedHints() throws Exception {
        JsonNode root = readBaseline();

        assertThat(root.path("formatVersion").asInt()).isEqualTo(1);
        assertThat(root.path("hintPolicy").asText())
            .isEqualTo("DISABLED_UNLESS_PRODUCTION_EXPLAIN_ANALYZE_EVIDENCE");
        assertPositive(root.path("dataScale"), "reviewTaskRows");
        assertPositive(root.path("dataScale"), "changedFileRows");
        assertPositive(root.path("dataScale"), "reviewFindingRows");
        assertPositive(root.path("dataScale"), "githubCommentPublicationRows");
        assertThat(root.path("sampling").path("explainCommand").asText()).isEqualTo("EXPLAIN ANALYZE");
        assertThat(root.path("sampling").path("requireActualRowsAndTime").asBoolean()).isTrue();
        assertThat(root.path("alerting").path("productionEvidenceRequiredForHint").asBoolean()).isTrue();
    }

    @Test
    void everyBaselineQueryReferencesMapperSqlAndKnownIndexesWithoutForceHint() throws Exception {
        JsonNode root = readBaseline();
        String migrations = migrationSql();
        Set<String> ids = new HashSet<>();

        assertThat(root.path("queries").isArray()).isTrue();
        assertThat(root.path("queries").size()).isPositive();
        for (JsonNode query : root.path("queries")) {
            String id = query.path("id").asText();
            String mapperName = query.path("mapper").asText();
            QueryBinding binding = BINDINGS.get(mapperName);
            assertThat(ids.add(id)).as("duplicate baseline query id").isTrue();
            assertThat(binding).as(mapperName + " binding").isNotNull();
            Method method = binding.method();
            Select select = method.getAnnotation(Select.class);
            assertThat(select).as(mapperName + " @Select").isNotNull();
            String sql = String.join("\n", select.value()).toLowerCase(Locale.ROOT);
            assertThat(sql).as(mapperName + " SQL").doesNotContain("force index");
            assertThat(query.path("supportingIndexes").size()).isPositive();
            for (JsonNode index : query.path("supportingIndexes")) {
                assertThat(migrations)
                    .as(mapperName + " supporting index " + index.asText())
                    .contains(index.asText().toLowerCase(Locale.ROOT));
            }
            assertThat(query.path("acceptableAccessTypes").size()).isPositive();
            assertThat(query.path("maxP95Milliseconds").asLong()).isPositive();
            assertThat(query.path("maxP99Milliseconds").asLong()).isPositive();
        }
        assertThat(ids).hasSize(BINDINGS.size());
    }

    private JsonNode readBaseline() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/db/query-plan-baseline.json")) {
            assertThat(stream).as("query plan baseline resource").isNotNull();
            return JSON.readTree(stream);
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

    private void assertPositive(JsonNode parent, String field) {
        assertThat(parent.path(field).asLong()).as(field).isPositive();
    }

    private record QueryBinding(Class<?> mapperClass, String methodName, Class<?>... parameterTypes) {

        private Method method() throws NoSuchMethodException {
            return mapperClass.getMethod(methodName, parameterTypes);
        }
    }
}
