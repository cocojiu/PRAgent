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
    void latestReviewTaskDateQueryKeepsCreatedAtMaxContract() throws Exception {
        String sql = sql("selectLatestReviewTaskDate");

        assertThat(sql)
            .contains("select date(max(created_at))")
            .contains("from review_task");
    }

    @Test
    void metricQueryKeepsBoundedAggregateContract() throws Exception {
        String sql = sql("selectMetricStat", LocalDate.class);

        assertThat(sql)
            .contains("from review_task")
            .contains("created_at >= #{startdate}")
            .contains("count(*) as total")
            .contains("risk_level_norm in ('high', 'critical')")
            .contains("status_norm = 'failed'")
            .contains("avg(coalesce(duration_seconds, 0)) as averagedurationseconds");
        assertNoRuntimeDashboardNormalization(sql);
    }

    @Test
    void llmQualityTrendQueryKeepsBoundedDailyAggregationContract() throws Exception {
        String sql = sql("selectLlmQualityTrendCounts", LocalDate.class);

        assertThat(sql)
            .contains("from review_task")
            .contains("llm_status_norm <> ''")
            .contains("created_at >= #{startdate}")
            .contains("date_format(created_date, '%y-%m-%d') as daykey")
            .contains("group by created_date");
        assertLlmQualityStatusNormalization(sql);
        assertNoRuntimeDashboardNormalization(sql);
    }

    @Test
    void reviewTrendQueryKeepsBoundedDailyAggregationContract() throws Exception {
        String sql = sql("selectReviewTrendCounts", LocalDate.class);

        assertThat(sql)
            .contains("from review_task")
            .contains("created_at >= #{startdate}")
            .contains("date_format(created_date, '%m-%d') as daylabel")
            .contains("group by created_date")
            .contains("order by created_date");
    }

    @Test
    void riskDistributionQueryKeepsBoundedGroupContract() throws Exception {
        String sql = sql("selectRiskLevelCounts", LocalDate.class);

        assertThat(sql)
            .contains("from review_task")
            .contains("created_at >= #{startdate}")
            .contains("risk_bucket_norm as risklevel")
            .contains("count(*) as total")
            .contains("group by risk_bucket_norm");
        assertRiskLevelBucketNormalization(sql);
        assertNoRuntimeDashboardNormalization(sql);
    }

    @Test
    void recentHighRiskQueryKeepsRiskFilterOrderAndLimitContract() throws Exception {
        String sql = sql("selectRecentHighRiskReviews", LocalDate.class);

        assertThat(sql)
            .contains("from review_task t")
            .contains("left join review_finding f on f.task_id = t.id and f.category = 'finding'")
            .contains("t.risk_level_norm as risklevel")
            .contains("where t.risk_level_norm in ('high', 'critical')")
            .contains("t.created_at >= #{startdate}")
            .contains("order by t.created_at desc")
            .contains("limit 5");
        assertNoRuntimeDashboardNormalization(sql);
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
        String byModelSql = sql("selectLlmQualityByModelStats", LocalDate.class);
        String byRepositorySql = sql("selectLlmQualityByRepositoryStats", LocalDate.class);

        assertThat(byModelSql)
            .contains("from review_task")
            .contains("llm_status_norm <> ''")
            .contains("created_at >= #{startdate}")
            .contains("t.created_at >= #{startdate}")
            .contains("join review_finding f on f.task_id = t.id and f.category = 'finding'")
            .contains("feedback_status_norm")
            .contains("order by task_stats.taskcount desc")
            .contains("limit 6");
        assertThat(byRepositorySql)
            .contains("from review_task")
            .contains("llm_status_norm <> ''")
            .contains("created_at >= #{startdate}")
            .contains("t.created_at >= #{startdate}")
            .contains("join review_finding f on f.task_id = t.id and f.category = 'finding'")
            .contains("feedback_status_norm")
            .contains("order by task_stats.taskcount desc")
            .contains("limit 6");
        assertLlmQualityStatusNormalization(byModelSql);
        assertLlmQualityStatusNormalization(byRepositorySql);
        assertFeedbackStatusNormalization(byModelSql);
        assertFeedbackStatusNormalization(byRepositorySql);
        assertNoRuntimeDashboardNormalization(byModelSql);
        assertNoRuntimeDashboardNormalization(byRepositorySql);
    }

    @Test
    void migrationsKeepDashboardIndexAssumptions() throws IOException {
        String migrations = migrationSql();

        assertThat(migrations)
            .contains("idx_review_task_created_at")
            .contains("idx_review_task_risk_created")
            .contains("idx_review_task_dashboard_created_risk")
            .contains("idx_review_task_dashboard_created_risk_norm")
            .contains("idx_review_task_dashboard_created_day")
            .contains("idx_review_task_dashboard_created_llm_model")
            .contains("idx_review_task_dashboard_created_llm_model_norm")
            .contains("idx_review_task_dashboard_created_llm_repo")
            .contains("idx_review_task_dashboard_created_llm_repo_norm")
            .contains("idx_review_finding_task_category_rule")
            .contains("idx_review_finding_task_category_feedback_norm")
            .contains("idx_review_task_pr_created")
            .contains("idx_review_task_commit_created")
            .contains("idx_review_task_mq_health");
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

    private void assertLlmQualityStatusNormalization(String sql) {
        assertThat(sql)
            .contains("llm_status_norm <> 'pending'")
            .contains("llm_status_norm = 'fallback'")
            .contains("llm_parse_status_norm = 'fallback'")
            .contains("llm_parse_status_norm = 'partial_fallback'");
    }

    private void assertFeedbackStatusNormalization(String sql) {
        assertThat(sql)
            .contains("f.feedback_status_norm <> 'unreviewed'")
            .contains("f.feedback_status_norm = 'valid'")
            .contains("f.feedback_status_norm = 'false_positive'");
    }

    private void assertRiskLevelBucketNormalization(String sql) {
        assertThat(sql)
            .contains("risk_bucket_norm as risklevel")
            .contains("group by risk_bucket_norm");
    }

    private void assertNoRuntimeDashboardNormalization(String sql) {
        assertThat(sql)
            .doesNotContain("upper(coalesce(nullif(trim(")
            .doesNotContain("lower(coalesce(nullif(trim(")
            .doesNotContain("group by date_format(created_at");
    }
}
