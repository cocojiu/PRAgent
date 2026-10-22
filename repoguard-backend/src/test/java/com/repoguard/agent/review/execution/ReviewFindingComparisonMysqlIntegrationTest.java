package com.repoguard.agent.review.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.repoguard.agent.config.MybatisPlusConfig;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Synthetic fixture restricted to a dedicated local database or ephemeral CI service. */
@EnabledIf("databaseEnabled")
class ReviewFindingComparisonMysqlIntegrationTest {
    private static JdbcTemplate jdbc;
    private static JdbcTemplate fixtureDdl;
    private static TransactionTemplate transactions;
    private static ReviewFindingComparisonService service;
    private static ReviewFindingMapper findingReads;
    private static final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private static final Map<String, Object> results = new LinkedHashMap<>();

    static boolean databaseEnabled() {
        return "true".equals(System.getenv("OPT052_RUN_LOCAL_MYSQL"))
            || "true".equals(System.getenv("REPOGUARD_RUN_INTEGRATION_TESTS"));
    }

    private static Map<String, String> sourceHashes() throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String name : List.of("review/execution/ReviewFindingComparisonService.java",
            "mapper/OperationalDataRetentionMapper.java", "retention/OperationalDataRetentionWorker.java",
            "retention/OperationalDataRetentionBatchExecutor.java")) {
            byte[] bytes = Files.readAllBytes(Path.of("src/main/java/com/repoguard/agent", name));
            hashes.put(name, java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        return hashes;
    }
    @BeforeAll static void open() throws Exception {
        boolean local = "true".equals(System.getenv("OPT052_RUN_LOCAL_MYSQL"));
        String url = System.getenv().getOrDefault("OPT052_DATABASE_URL", System.getenv("SPRING_DATASOURCE_URL"));
        if (local) assertThat(url).startsWith("jdbc:mysql://127.0.0.1:33482/opt052_20261022?");
        else {
            assertThat(System.getenv("CI")).isEqualTo("true");
            assertThat(url).startsWith("jdbc:mysql://127.0.0.1:3306/repoguard_integration?");
        }
        var data = new DriverManagerDataSource(url, local ? "root" : System.getenv("SPRING_DATASOURCE_USERNAME"),
            System.getenv().getOrDefault("OPT052_DATABASE_PASSWORD", System.getenv("SPRING_DATASOURCE_PASSWORD")));
        jdbc = new JdbcTemplate(data);
        fixtureDdl = local ? jdbc : new JdbcTemplate(new DriverManagerDataSource(url, "root",
            System.getenv("REPOGUARD_MYSQL_DIAGNOSTICS_PASSWORD")));
        jdbc.setQueryTimeout(5);
        if (local) assertThat(jdbc.queryForObject("select @@datadir", String.class).replace('\\', '/'))
            .contains("optimization-049-054-evidence/mysql052/data");
        results.put("sourceRevision", System.getenv().getOrDefault("GITHUB_SHA", "local-working-tree"));
        results.put("sourceHashes", sourceHashes());
        results.put("mysqlVersion", jdbc.queryForObject("select version()", String.class));
        results.put("environment", local ? "isolated local MySQL; 128MiB InnoDB; loopback; synthetic data" : "ephemeral CI MySQL; loopback; synthetic data");
        Flyway.configure().dataSource(data).cleanDisabled(true)
            .initSql("SET SESSION sql_mode = REPLACE(@@SESSION.sql_mode, 'ONLY_FULL_GROUP_BY', '')")
            .locations("classpath:db/migration").load().migrate();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(ReviewFindingMapper.class, ReviewTaskMapper.class, ReviewExecutionAttemptMapper.class))
            configuration.addMapper(mapper);
        var tenancy = new TenantProperties(); tenancy.setEnabled(true);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(data);
        factory.setConfiguration(configuration);
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor(tenancy));
        var session = new SqlSessionTemplate(factory.getObject());
        findingReads = session.getMapper(ReviewFindingMapper.class);
        service = new ReviewFindingComparisonService(session.getMapper(ReviewTaskMapper.class),
            session.getMapper(ReviewExecutionAttemptMapper.class), session.getMapper(ReviewFindingMapper.class), factory.getObject());
        service.recordMetrics(metrics);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(data));
        transactions.setTimeout(15);
    }

    @AfterAll static void save() throws Exception {
        String target = System.getenv("OPT052_MATRIX_OUTPUT");
        Path output = target == null || target.isBlank() ? Path.of("target/optimization-acceptance/comparison-mysql.json") : Path.of(target);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,
            new com.repoguard.agent.config.JacksonConfig().objectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(results));
        metrics.close();
    }

    @Test void realMapperCapacityAndIdempotencyMatrix() {
        List<Map<String, Object>> rows = new ArrayList<>();
        results.put("matrix", rows);
        for (int size : new int[] {10, 100, 1000, 5000}) {
            long[] fixture = seed(size);
            long tenant = fixture[0], task = fixture[1], attempt = fixture[2];
            List<Double> milliseconds = new ArrayList<>();
            for (int repeat = 0; repeat < 3; repeat++) {
                jdbc.update("update review_finding set comparison_attempt_id=null where tenant_id=? and task_id=?", tenant, task);
                double before = writes();
                long startedAt = System.nanoTime();
                try (var _ = TenantContext.withTenant(tenant)) {
                    transactions.executeWithoutResult(status -> service.materializeCurrent(task, attempt));
                }
                milliseconds.add((System.nanoTime() - startedAt) / 1_000_000d);
                assertThat(writes() - before).isEqualTo(size);
                assertThat(jdbc.queryForObject("select count(*) from review_finding where tenant_id=? and task_id=? and comparison_attempt_id=?",
                    Long.class, tenant, task, attempt)).isEqualTo(size);
            }
            double before = writes();
            long startedAt = System.nanoTime();
            try (var _ = TenantContext.withTenant(tenant)) {
                transactions.executeWithoutResult(status -> service.materializeCurrent(task, attempt));
            }
            double idempotentMillis = (System.nanoTime() - startedAt) / 1_000_000d;
            assertThat(writes() - before).isZero();
            try (var _ = TenantContext.withTenant(tenant + 1000000)) {
                assertThatThrownBy(() -> transactions.executeWithoutResult(status -> service.materializeCurrent(task, attempt)))
                    .isInstanceOf(com.repoguard.agent.common.BusinessException.class);
            }
            rows.add(Map.of("findings", size, "materializeMillis", milliseconds, "idempotentMillis", idempotentMillis,
                "writesPerMaterialization", size, "idempotentWrites", 0, "foreignTenantRejected", true));
        }
        results.put("matrixCompleted", true);
    }

    @Test void realDatabaseFailureRollsBackEveryEarlierFinding() {
        long[] fixture = seed(501);
        long tenant = fixture[0], task = fixture[1], attempt = fixture[2];
        long last = jdbc.queryForObject("select max(id) from review_finding where tenant_id=? and task_id=?", Long.class, tenant, task);
        fixtureDdl.execute("create trigger opt052_reject_last_" + task + " before update on review_finding for each row begin "
            + "if new.id=" + last + " then signal sqlstate '45000' set message_text='synthetic last finding rejection'; end if; end");
        try (var _ = TenantContext.withTenant(tenant)) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> service.materializeCurrent(task, attempt)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from review_finding where tenant_id=? and task_id=? and comparison_attempt_id is not null",
            Long.class, tenant, task)).isZero();
        assertThat(metrics.get("repoguard.review.comparison.transaction.duration").tag("outcome", "rolled_back")
            .timer().count()).isPositive();
        results.put("lastFindingFailureRolledBackAll501Rows", true);
    }

    @Test void realBatchUpdatesAreVisibleToReadsInSameTransaction() {
        long[] fixture = seed(3);
        long tenant = fixture[0], task = fixture[1], attempt = fixture[2];
        try (var _ = TenantContext.withTenant(tenant)) {
            transactions.executeWithoutResult(status -> {
                assertThat(findingReads.selectAttemptForComparison(task, attempt))
                    .allMatch(finding -> finding.getComparisonAttemptId() == null);
                service.materializeCurrent(task, attempt);
                assertThat(findingReads.selectAttemptForComparison(task, attempt))
                    .allMatch(finding -> java.util.Objects.equals(finding.getComparisonAttemptId(), attempt));
            });
        }
        results.put("readAfterWriteInSameTransaction", true);
    }

    private static double writes() {
        var summary = metrics.find("repoguard.review.comparison.writes").summary();
        return summary == null ? 0 : summary.totalAmount();
    }

    private static long[] seed(int count) {
        long tenant = insert("insert into tenant(tenant_key,display_name) values(?,?)",
            "opt052-" + java.util.UUID.randomUUID(), "synthetic capacity");
        jdbc.update("update tenant set status='SUSPENDED' where id=?", tenant);
        long task = insert("insert into review_task(tenant_id,pr_number,title,repository,organization,commit_sha,branch_name,status,risk_level,llm_status,created_at) "
            + "values(?,1,'capacity fixture','repo','org',?,'main','COMPLETED','LOW','COMPLETED',utc_timestamp())", tenant, "a".repeat(40));
        long attempt = insert("insert into review_execution_attempt(tenant_id,task_id,attempt_no,generation,commit_sha,input_fingerprint,status,queued_at,started_at,created_at) "
            + "values(?,?,1,1,?,?,'COMPLETED',utc_timestamp(),utc_timestamp(),utc_timestamp())", tenant, task, "a".repeat(40), "b".repeat(64));
        jdbc.update("update review_task set current_attempt_id=? where id=? and tenant_id=?", attempt, task, tenant);
        List<Object[]> findings = new ArrayList<>();
        for (int index = 0; index < count; index++) findings.add(new Object[] {tenant, task, attempt, index + 1, "synthetic finding " + index});
        jdbc.batchUpdate("insert into review_finding(tenant_id,task_id,attempt_id,category,file_path,line_number,message,source,severity,rule_id,current_attempt) "
            + "values(?,?,?,'FINDING','src/A.java',?,?,'RULE','LOW','SYNTHETIC_CAPACITY',1)", findings);
        return new long[] {tenant, task, attempt};
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
