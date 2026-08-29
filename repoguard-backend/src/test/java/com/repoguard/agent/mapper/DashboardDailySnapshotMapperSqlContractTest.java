package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class DashboardDailySnapshotMapperSqlContractTest {

    @Test
    void migrationDefinesDailySnapshotTablesAndLookupIndexes() throws IOException {
        String sql = Files.readString(Path.of(
            "src/main/resources/db/migration/V46__dashboard_daily_snapshot_tables.sql"
        )).toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("create table if not exists dashboard_review_daily_stat")
            .contains("create table if not exists dashboard_rule_daily_stat")
            .contains("create table if not exists dashboard_llm_quality_daily_stat")
            .contains("primary key (stat_date, rule_id)")
            .contains("primary key (stat_date, model_label, repository_label)")
            .contains("idx_dashboard_llm_daily_model_date")
            .contains("idx_dashboard_llm_daily_repository_date");

        String refreshStateSql = Files.readString(Path.of(
            "src/main/resources/db/migration/V61__dashboard_snapshot_refresh_state.sql"
        )).toLowerCase(Locale.ROOT);
        assertThat(refreshStateSql)
            .contains("create table if not exists dashboard_daily_snapshot_refresh_state")
            .contains("review_version bigint unsigned")
            .contains("review_refreshed_version bigint unsigned")
            .contains("llm_quality_version bigint unsigned")
            .contains("llm_quality_refreshed_version bigint unsigned")
            .contains("idx_review_task_dashboard_stat_date (created_date)");
    }

    @Test
    void dashboardReadQueriesUsePersistedSnapshotTables() throws Exception {
        assertSnapshotRead("selectMetricStat", "dashboard_review_daily_stat");
        assertSnapshotRead("selectReviewTrendCounts", "dashboard_review_daily_stat");
        assertSnapshotRead("selectRiskLevelCounts", "dashboard_review_daily_stat");
        assertSnapshotRead("selectRuleHitCounts", "dashboard_rule_daily_stat");
        assertSnapshotRead("selectLlmQualityTrendCounts", "dashboard_llm_quality_daily_stat");
        assertSnapshotRead("selectLlmQualityByModelStats", "dashboard_llm_quality_daily_stat");
        assertSnapshotRead("selectLlmQualityByRepositoryStats", "dashboard_llm_quality_daily_stat");
    }

    @Test
    void reviewSnapshotRefreshQueriesUseGeneratedDashboardColumns() throws Exception {
        String reviewRefresh = insertSql("insertReviewDailyStatsForDate");
        String ruleRefresh = insertSql("insertRuleDailyStatsForDate");

        assertThat(reviewRefresh)
            .contains("insert into dashboard_review_daily_stat ( tenant_id,")
            .contains("from review_task")
            .contains("tenant_id as tenant_id")
            .contains("created_date as stat_date")
            .contains("where created_date = #{statdate}")
            .contains("group by tenant_id, created_date")
            .contains("risk_bucket_norm")
            .contains("status_norm")
            .contains("assessment_status = 'complete'");
        assertThat(ruleRefresh)
            .contains("insert into dashboard_rule_daily_stat ( tenant_id,")
            .contains("from review_finding f")
            .contains("join review_task t on t.id = f.task_id")
            .contains("t.tenant_id as tenant_id")
            .contains("t.created_date as stat_date")
            .contains("t.created_date = #{statdate}")
            .contains("group by t.tenant_id, t.created_date");
    }

    @Test
    void llmSnapshotRefreshQueryUsesGeneratedLabelsAndFeedbackStatus() throws Exception {
        String sql = insertSql("insertLlmQualityDailyStatsForDate");

        assertThat(sql)
            .contains("insert into dashboard_llm_quality_daily_stat ( tenant_id,")
            .contains("task_stats.tenant_id as tenant_id")
            .contains("tenant_id as tenant_id, created_date as stat_date")
            .contains("t.tenant_id as tenant_id")
            .contains("feedback_stats.tenant_id = task_stats.tenant_id")
            .contains("llm_model_label as model_label")
            .contains("repository_label as repository_label")
            .contains("llm_status_norm <> ''")
            .contains("llm_parse_status_norm = 'parsed'")
            .contains("f.feedback_status_norm <> 'unreviewed'")
            .contains("created_date = #{statdate}")
            .contains("t.created_date = #{statdate}");
    }

    @Test
    void snapshotRefreshDeletesOnlyTheRequestedDate() throws Exception {
        assertThat(deleteSql("deleteReviewDailyStatsOn"))
            .contains("delete from dashboard_review_daily_stat")
            .contains("where stat_date = #{statdate}");
        assertThat(deleteSql("deleteRuleDailyStatsOn"))
            .contains("delete from dashboard_rule_daily_stat")
            .contains("where stat_date = #{statdate}");
        assertThat(deleteSql("deleteLlmQualityDailyStatsOn"))
            .contains("delete from dashboard_llm_quality_daily_stat")
            .contains("where stat_date = #{statdate}");
    }

    @Test
    void refreshStateUsesMonotonicVersionsSoConcurrentWritesRemainDirty() throws Exception {
        assertThat(insertSql("markReviewActivityDirty"))
            .contains("review_version = review_version + 1")
            .contains("llm_quality_version = llm_quality_version + 1");
        assertThat(updateSql("markReviewRefreshed"))
            .contains("review_refreshed_version = greatest(review_refreshed_version, #{version})")
            .doesNotContain("review_version = #{version}");
        assertThat(updateSql("markLlmQualityRefreshed"))
            .contains("llm_quality_refreshed_version = greatest(llm_quality_refreshed_version, #{version})")
            .doesNotContain("llm_quality_version = #{version}");
    }

    private void assertSnapshotRead(String methodName, String tableName) throws Exception {
        String sql = selectSql(methodName);
        assertThat(sql)
            .contains("from " + tableName)
            .doesNotContain("from review_task")
            .doesNotContain("join review_finding");
    }

    private String selectSql(String methodName) throws NoSuchMethodException {
        Method method = DashboardDailySnapshotMapper.class.getMethod(methodName, LocalDate.class);
        return normalize(String.join("\n", method.getAnnotation(Select.class).value()));
    }

    private String insertSql(String methodName) throws NoSuchMethodException {
        Method method = DashboardDailySnapshotMapper.class.getMethod(methodName, LocalDate.class);
        return normalize(String.join("\n", method.getAnnotation(Insert.class).value()));
    }

    private String deleteSql(String methodName) throws NoSuchMethodException {
        Method method = DashboardDailySnapshotMapper.class.getMethod(methodName, LocalDate.class);
        return normalize(String.join("\n", method.getAnnotation(Delete.class).value()));
    }

    private String updateSql(String methodName) throws NoSuchMethodException {
        Method method = DashboardDailySnapshotMapper.class.getMethod(methodName, LocalDate.class, long.class);
        return normalize(String.join("\n", method.getAnnotation(Update.class).value()));
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
