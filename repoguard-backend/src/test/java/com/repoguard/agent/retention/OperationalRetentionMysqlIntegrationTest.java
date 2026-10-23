package com.repoguard.agent.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.repoguard.agent.config.MybatisPlusConfig;
import com.repoguard.agent.config.OperationalDataRetentionProperties;
import com.repoguard.agent.mapper.OperationalDataRetentionMapper;
import com.repoguard.agent.tenancy.ScheduledJobLeaseContext;
import com.repoguard.agent.tenancy.ScheduledJobLeaseGuard;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@EnabledIf("databaseEnabled")
class OperationalRetentionMysqlIntegrationTest {
    private static JdbcTemplate jdbc;
    private static JdbcTemplate fixtureDdl;
    private static OperationalDataRetentionMapper mapper;
    private static OperationalDataRetentionBatchExecutor batches;
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
        jdbc = new JdbcTemplate(data); jdbc.setQueryTimeout(5);
        fixtureDdl = local ? jdbc : new JdbcTemplate(new DriverManagerDataSource(url, "root",
            System.getenv("REPOGUARD_MYSQL_DIAGNOSTICS_PASSWORD")));
        if (local) assertThat(jdbc.queryForObject("select @@datadir", String.class).replace('\\', '/'))
            .contains("optimization-049-054-evidence/mysql052/data");
        results.put("sourceRevision", System.getenv().getOrDefault("GITHUB_SHA", "local-working-tree"));
        results.put("sourceHashes", sourceHashes());
        results.put("mysqlVersion", jdbc.queryForObject("select version()", String.class));
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(OperationalDataRetentionMapper.class);
        var tenant = new TenantProperties(); tenant.setEnabled(true);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(data); factory.setConfiguration(configuration);
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor(tenant));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(OperationalDataRetentionMapper.class);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(new DataSourceTransactionManager(data));
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        advice.afterPropertiesSet();
        var proxy = new ProxyFactory(new OperationalDataRetentionBatchExecutor(mapper));
        proxy.setProxyTargetClass(true); proxy.addAdvice(advice);
        batches = (OperationalDataRetentionBatchExecutor) proxy.getProxy();
    }

    @AfterAll static void save() throws Exception {
        String target = System.getenv("OPT052_MATRIX_OUTPUT");
        Path output = target == null || target.isBlank() ? Path.of("target/optimization-acceptance/retention-mysql.json") : Path.of(target);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,
            new com.repoguard.agent.config.JacksonConfig().objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
        metrics.close();
    }

    @Test void realDeletesRespectSharedRowBudgetAndResume() {
        long tenant = seed(9);
        var properties = properties(); properties.setBatchSize(4); properties.setMaxRowsPerRun(5);
        var worker = new OperationalDataRetentionWorker(mapper, properties, metrics, batches);
        try (var _ = TenantContext.withTenant(tenant)) {
            worker.cleanupTenantData();
            assertThat(logs(tenant)).isEqualTo(4);
            worker.cleanupTenantData();
            assertThat(logs(tenant)).isZero();
        }
        assertThat(jdbc.queryForObject("select sum(deleted_rows) from operational_data_cleanup_audit where tenant_id=? and table_name='system_setting_log' and status='SUCCESS'",
            Long.class, tenant)).isEqualTo(9);
        results.put("sharedRowBudgetAndResume", true);
    }

    @Test void realAuditFailureRollsBackDeletion() {
        long tenant = seed(9);
        fixtureDdl.execute("create trigger opt053_audit_" + tenant + " before insert on operational_data_cleanup_audit for each row begin "
            + "if new.tenant_id=" + tenant + " and new.table_name='system_setting_log' and new.status='SUCCESS' "
            + "then signal sqlstate '45000' set message_text='synthetic audit rejection'; end if; end");
        try (var _ = TenantContext.withTenant(tenant)) {
            new OperationalDataRetentionWorker(mapper, properties(), metrics, batches).cleanupTenantData();
        }
        assertThat(logs(tenant)).isEqualTo(9);
        assertThat(failures(tenant)).isEqualTo(1);
        results.put("auditFailureRollsBackRealDelete", true);
    }

    @Test @SuppressWarnings("unchecked")
    void simulatedLeaseLossRollsBackRealDelete() {
        for (int first = 0; first < 4; first++) {
            long tenant = seed(9);
            var worker = new OperationalDataRetentionWorker(mapper, properties(), metrics, batches);
            var starts = (com.github.benmanes.caffeine.cache.Cache<Long, Integer>)
                org.springframework.test.util.ReflectionTestUtils.getField(worker, "nextTable");
            java.util.Objects.requireNonNull(starts).put(tenant, first);
            var guard = org.mockito.Mockito.mock(ScheduledJobLeaseGuard.class);
            var lost = new java.util.concurrent.atomic.AtomicBoolean();
            org.mockito.Mockito.doAnswer(invocation -> {
                if (lost.get() || logs(tenant) == 0) {
                    lost.set(true);
                    throw new IllegalStateException("synthetic lease loss after actual deletion");
                }
                return null;
            }).when(guard).assertHeld();
            try (var _ = TenantContext.withTenant(tenant); var _ = ScheduledJobLeaseContext.withGuard(guard)) {
                // Exercise the production lease wrapper, including its post-task ownership check.
                var task = ScheduledJobLeaseContext.capture(worker::cleanupTenantData);
                assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
            }
            assertThat(logs(tenant)).isEqualTo(9);
            assertThat(lost).isTrue();
        }
        results.put("simulatedLeaseLossRollsBackRealDelete", true);
        results.put("leaseLossInitialTablePositions", List.of(0, 1, 2, 3));
    }

    @Test void realSlowDeleteIsCancelledByTransactionTimeout() {
        long tenant = seed(1);
        fixtureDdl.execute("create trigger opt053_slow_" + tenant + " before delete on system_setting_log for each row begin "
            + "if old.tenant_id=" + tenant + " then do sleep(6); end if; end");
        var properties = properties(); properties.setBatchSize(1); properties.setMaxRowsPerRun(1);
        long startedAt = System.nanoTime();
        try (var _ = TenantContext.withTenant(tenant)) {
            new OperationalDataRetentionWorker(mapper, properties, metrics, batches).cleanupTenantData();
        }
        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(logs(tenant)).isEqualTo(1);
        assertThat(failures(tenant)).isEqualTo(1);
        assertThat(millis).isBetween(4000L, 12000L);
        results.put("slowDeleteTimeoutMillis", millis);
        results.put("slowDeleteRolledBack", true);
    }

    private static OperationalDataRetentionProperties properties() {
        var properties = new OperationalDataRetentionProperties(); properties.setBatchSize(20);
        properties.setMaxRunMillis(20000); return properties;
    }

    private static long logs(long tenant) {
        return jdbc.queryForObject("select count(*) from system_setting_log where tenant_id=?", Long.class, tenant);
    }

    private static long failures(long tenant) {
        return jdbc.queryForObject("select count(*) from operational_data_cleanup_audit where tenant_id=? and table_name='system_setting_log' and status='FAILED'",
            Long.class, tenant);
    }

    private static long seed(int rows) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("insert into tenant(tenant_key,display_name) values(?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "opt053-" + java.util.UUID.randomUUID()); statement.setString(2, "synthetic retention"); return statement;
        }, keys);
        long tenant = java.util.Objects.requireNonNull(keys.getKey()).longValue();
        jdbc.update("update tenant set status='SUSPENDED' where id=?", tenant);
        for (int index = 0; index < rows; index++) jdbc.update(
            "insert into system_setting_log(tenant_id,operator,action,status,created_at) values(?,'fixture','fixture','SUCCESS',utc_timestamp()-interval 400 day)", tenant);
        return tenant;
    }
}
