package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class DashboardMapperSqlContractTest {

    @Test
    void metricQueryKeepsBoundedAggregateContract() throws Exception {
        String sql = sql("selectMetricStat", LocalDate.class);

        assertThat(sql)
            .contains("from review_task")
            .contains("created_at >= #{startdate}")
            .contains("count(*) as total")
            .contains("risk_level in ('high', 'critical')")
            .contains("status = 'failed'")
            .contains("avg(coalesce(duration_seconds, 0)) as averagedurationseconds");
    }

    @Test
    void llmQualityTrendQueryKeepsBoundedDailyAggregationContract() throws Exception {
        String sql = sql("selectLlmQualityTrendCounts", LocalDate.class);

        assertThat(sql)
            .contains("from review_task")
            .contains("llm_status is not null")
            .contains("llm_status <> ''")
            .contains("llm_status <> 'pending'")
            .contains("created_at >= #{startdate}")
            .contains("date_format(created_at, '%y-%m-%d') as daykey")
            .contains("group by date_format(created_at, '%y-%m-%d')");
    }

    @Test
    void reviewTrendQueryKeepsBoundedDailyAggregationContract() throws Exception {
        String sql = sql("selectReviewTrendCounts", LocalDate.class);

        assertThat(sql)
            .contains("from review_task")
            .contains("created_at >= #{startdate}")
            .contains("date_format(created_at, '%m-%d') as daylabel")
            .contains("group by date_format(created_at, '%m-%d')")
            .contains("order by daylabel");
    }

    @Test
    void riskDistributionQueryKeepsBoundedGroupContract() throws Exception {
        String sql = sql("selectRiskLevelCounts", LocalDate.class);

        assertThat(sql)
            .contains("from review_task")
            .contains("created_at >= #{startdate}")
            .contains("risk_level as risklevel")
            .contains("count(*) as total")
            .contains("group by risk_level");
    }

    @Test
    void recentHighRiskQueryKeepsRiskFilterOrderAndLimitContract() throws Exception {
        String sql = sql("selectRecentHighRiskReviews");

        assertThat(sql)
            .contains("from review_task t")
            .contains("left join review_finding f on f.task_id = t.id and f.category = 'finding'")
            .contains("where t.risk_level in ('high', 'critical')")
            .contains("order by t.created_at desc")
            .contains("limit 5");
    }

    @Test
    void ruleHitQueryKeepsFindingCategoryAndLlmFallbackContract() throws Exception {
        String sql = sql("selectRuleHitCounts", LocalDate.class);

        assertThat(sql)
            .contains("from review_finding f")
            .contains("join review_task t on t.id = f.task_id")
            .contains("coalesce(f.rule_id, 'llm') as ruleid")
            .contains("where f.category = 'finding'")
            .contains("t.created_at >= #{startdate}")
            .contains("group by coalesce(f.rule_id, 'llm')");
    }

    @Test
    void llmQualityBreakdownQueriesKeepFeedbackJoinContract() throws Exception {
        String byModelSql = sql("selectLlmQualityByModelStats");
        String byRepositorySql = sql("selectLlmQualityByRepositoryStats");

        assertThat(byModelSql)
            .contains("from review_task")
            .contains("llm_status is not null")
            .contains("llm_status <> 'pending'")
            .contains("join review_finding f on f.task_id = t.id and f.category = 'finding'")
            .contains("feedback_status")
            .contains("order by task_stats.taskcount desc")
            .contains("limit 6");
        assertThat(byRepositorySql)
            .contains("from review_task")
            .contains("llm_status is not null")
            .contains("llm_status <> 'pending'")
            .contains("join review_finding f on f.task_id = t.id and f.category = 'finding'")
            .contains("feedback_status")
            .contains("order by task_stats.taskcount desc")
            .contains("limit 6");
    }

    @Test
    void migrationsKeepDashboardIndexAssumptions() throws IOException {
        String migrations = migrationSql();

        assertThat(migrations)
            .contains("idx_review_task_created_at")
            .contains("idx_review_task_risk_created")
            .contains("idx_review_finding_category")
            .contains("idx_review_finding_task")
            .contains("idx_review_task_llm_quality");
    }

    private String sql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = DashboardMapper.class.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as(methodName + " @Select").isNotNull();
        return normalizeSql(String.join("\n", select.value()));
    }

    private String migrationSql() throws IOException {
        Path migrationDir = Path.of("src/main/resources/db/migration");
        try (var files = Files.walk(migrationDir)) {
            return normalizeSql(String.join("\n", files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted()
                .map(this::readString)
                .toList()));
        }
    }

    private String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read migration " + path, ex);
        }
    }

    private String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
