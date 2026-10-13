package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.repoguard.agent.config.MybatisPlusConfig;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.review.ReviewTaskCursorCodec;
import com.repoguard.agent.review.task.ReviewFailureSummaryResolver;
import com.repoguard.agent.review.task.ReviewTaskListItemAssembler;
import com.repoguard.agent.security.AuthProperties;
import com.repoguard.agent.service.impl.ReviewTaskListQueryBuilder;
import com.repoguard.agent.service.impl.ReviewTaskQueryItemLoader;
import com.repoguard.agent.service.impl.ReviewTimelineQueryService;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** Runs production mapper statements with the real tenant interceptor and MySQL parser. */
@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_INTEGRATION_TESTS", matches = "true")
class ReviewQueryOptimizationMysqlIntegrationTest {
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate session;
    private long tenant;
    private long otherTenant;
    private long task;
    private long foreignTask;
    private long attempt;
    private long foreignAttempt;

    @BeforeAll
    static void open() throws Exception {
        var data = new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),
            System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"),
            System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        jdbc = new JdbcTemplate(data);
        Flyway.configure().dataSource(data).cleanDisabled(true)
            .initSql("SET SESSION sql_mode = REPLACE(@@SESSION.sql_mode, 'ONLY_FULL_GROUP_BY', '')")
            .locations("classpath:db/migration").load().migrate();
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(ChangedFileMapper.class, ReviewFindingMapper.class,
            ReviewTaskMapper.class, ReviewTimelineMapper.class)) configuration.addMapper(mapper);
        var tenancy = new TenantProperties(); tenancy.setEnabled(true);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(data);
        factory.setConfiguration(configuration);
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor(tenancy));
        session = new SqlSessionTemplate(factory.getObject());
    }

    @BeforeEach
    void seed() {
        tenant = insert("insert into tenant(tenant_key,display_name) values(?,?)", UUID.randomUUID().toString(), "query fixture");
        otherTenant = insert("insert into tenant(tenant_key,display_name) values(?,?)", UUID.randomUUID().toString(), "foreign fixture");
        task = task(tenant); foreignTask = task(otherTenant);
        attempt = attempt(tenant, task); foreignAttempt = attempt(otherTenant, foreignTask);
    }

    @AfterEach
    void cleanup() {
        for (long id : new long[] {tenant, otherTenant}) {
            if (id == 0) continue;
            for (String table : new String[] {"review_finding", "changed_file", "review_timeline", "sarif_import_batch",
                "review_execution_attempt", "review_task", "tenant"}) {
                jdbc.update("delete from " + table + " where " + (table.equals("tenant") ? "id" : "tenant_id") + "=?", id);
            }
        }
    }

    @Test
    void fullFindingCountsExcludeOldAttemptAndNonFindingsAndKeepEmptyFiles() {
        long file = file(tenant, task, attempt, "src/A.java");
        long empty = file(tenant, task, attempt, "src/Empty.java");
        long foreign = file(otherTenant, foreignTask, foreignAttempt, "src/A.java");
        for (int index = 0; index < 30; index++) finding(tenant, task, attempt, "FINDING", 1);
        finding(tenant, task, attempt, "FINDING", 0); finding(tenant, task, attempt, "SUMMARY", 1);
        finding(otherTenant, foreignTask, foreignAttempt, "FINDING", 1);
        var mapper = session.getMapper(ChangedFileMapper.class);
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            assertThat(mapper.selectFindingCountsForFiles(task, List.of(file, empty, foreign)))
                .containsExactlyInAnyOrder(new ChangedFileMapper.FileFindingCount(file, 30L),
                    new ChangedFileMapper.FileFindingCount(empty, 0L));
            assertThat(mapper.selectFindingCountsForFiles(foreignTask, List.of(foreign))).isEmpty();
        }
    }

    @Test
    void batchExportMetadataIsTaskAndTenantScopedAndOnlyProjectsToolIdentity() {
        long first = batch(tenant, task, attempt, "1");
        long second = batch(tenant, task, attempt, "2");
        long foreign = batch(otherTenant, foreignTask, foreignAttempt, "foreign");
        var mapper = session.getMapper(ReviewFindingMapper.class);
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            var rows = mapper.selectSarifExportBatches(task, List.of(first, second, foreign));
            assertThat(rows).extracting(ReviewFindingMapper.SarifImportBatchRow::getToolVersion)
                .containsExactlyInAnyOrder("1", "2");
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.getTaskId()).isNull(); assertThat(row.getContentFingerprint()).isNull();
            });
            assertThat(mapper.selectSarifExportBatches(foreignTask, List.of(foreign))).isEmpty();
        }
    }

    @Test
    void narrowListAndLatestFailureSummaryPreserveThePublicListItem() {
        jdbc.update("update review_task set llm_prompt_summary=?,llm_cost_snapshot_json=JSON_OBJECT('large',?) where id=?",
            "p".repeat(1000), "c".repeat(30000), task);
        for (int index = 0; index < 500; index++) timeline(tenant, task, "normal event " + index, index);
        timeline(tenant, task, "Review failed: previous", 10);
        timeline(tenant, task, "Review failed: llm_timeout", 20);
        timeline(tenant, task, "review failed: wrong-case", 999);
        timeline(otherTenant, foreignTask, "Review failed: foreign", 999);
        var auth = new AuthProperties(); auth.setTokenSecret("query-fixture-key-for-cursor-codec-32-characters");
        var builder = new ReviewTaskListQueryBuilder(new ReviewTaskCursorCodec(auth));
        var tasks = session.getMapper(ReviewTaskMapper.class);
        var timelines = new ReviewTimelineQueryService(session.getMapper(ReviewTimelineMapper.class));
        var loader = new ReviewTaskQueryItemLoader(tasks, new ReviewFailureSummaryResolver(), timelines, new ReviewTaskListItemAssembler());
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            ReviewTask full = tasks.selectById(task);
            ReviewTask narrow = tasks.selectList(builder.build(new ReviewQuery(1, 20, null, null, null, null, null, null))).getFirst();
            assertThat(narrow.getId()).isEqualTo(task);
            assertThat(narrow.getLlmPromptSummary()).isNull(); assertThat(narrow.getLlmCostSnapshotJson()).isNull();
            assertThat(full.getLlmPromptSummary()).hasSize(1000);
            var all = timelines.loadByTaskId(task);
            var latest = loader.loadTimelinesByTaskId(List.of(narrow)).get(task);
            assertThat(all).hasSize(503); assertThat(latest).hasSize(1);
            assertThat(latest.getFirst().getLabel()).isEqualTo("Review failed: llm_timeout");
            assertThat(loader.assemble(narrow, latest)).isEqualTo(loader.assemble(full, all));
            assertThat(tasks.selectById(foreignTask)).isNull();
            assertThat(session.getMapper(ReviewTimelineMapper.class).selectLatestFailures(List.of(foreignTask))).isEmpty();
            System.out.printf("[query-fixture] timelineRowsBefore=%d after=%d omittedPayloadValueBytes=%d%n",
                all.size(), latest.size(), 31000);
        }
    }

    private static long task(long tenant) {
        return insert("""
            insert into review_task(tenant_id,pr_number,title,repository,organization,commit_sha,branch_name,
                status,risk_level,llm_status,created_at) values(?,1,'fixture','repo','org',?,'main','FAILED','LOW','FAILED',utc_timestamp())
            """, tenant, "a".repeat(40));
    }

    private static long attempt(long tenant, long task) {
        return insert("""
            insert into review_execution_attempt(tenant_id,task_id,attempt_no,generation,commit_sha,input_fingerprint,
                status,queued_at,started_at,created_at) values(?,?,1,1,?,?,'COMPLETED',utc_timestamp(),utc_timestamp(),utc_timestamp())
            """, tenant, task, "a".repeat(40), "b".repeat(64));
    }

    private static long file(long tenant, long task, long attempt, String path) {
        return insert("insert into changed_file(tenant_id,task_id,attempt_id,file_path,change_type) values(?,?,?,?,'MODIFIED')",
            tenant, task, attempt, path);
    }

    private static void finding(long tenant, long task, long attempt, String category, int current) {
        insert("insert into review_finding(tenant_id,task_id,attempt_id,category,file_path,current_attempt) values(?,?,?,?,'src/A.java',?)",
            tenant, task, attempt, category, current);
    }

    private static long batch(long tenant, long task, long attempt, String version) {
        return insert("""
            insert into sarif_import_batch(tenant_id,task_id,attempt_id,tool_name,tool_version,commit_sha,content_fingerprint)
                values(?,?,?,'fixture',?,?,?)
            """, tenant, task, attempt, version, "a".repeat(40), UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private static void timeline(long tenant, long task, String label, int order) {
        insert("insert into review_timeline(tenant_id,task_id,label,event_time,status,sort_order) values(?,?,?,utc_timestamp(),'DONE',?)",
            tenant, task, label, order);
    }

    private static long insert(String sql, Object... values) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, keys);
        return java.util.Objects.requireNonNull(keys.getKey()).longValue();
    }
}
