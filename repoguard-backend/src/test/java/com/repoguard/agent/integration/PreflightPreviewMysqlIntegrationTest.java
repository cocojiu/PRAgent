package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.mapper.PreflightPreviewMapper;
import com.repoguard.agent.preflight.PreflightPreviewStore;
import com.repoguard.agent.security.PreflightCredentialService.Grant;
import com.repoguard.agent.security.SecretCryptoService;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Only an independently initialized loopback fixture; never accepts a production database. */
@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_PREFLIGHT_INTEGRATION", matches = "true")
class PreflightPreviewMysqlIntegrationTest {
    private static DataSource source;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext context;
    private static PreflightPreviewStore store;
    private static PreflightPreviewMapper mapper;
    private long user;

    @BeforeAll static void start() {
        source = PreflightIntegrationDatabase.migrateFixture();
        jdbc = new JdbcTemplate(source);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source); context.register(Fixture.class); context.refresh();
        store = context.getBean(PreflightPreviewStore.class); mapper = context.getBean(PreflightPreviewMapper.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(store)).isTrue();
    }

    @AfterAll static void close() { if (context != null) context.close(); }
    @BeforeEach void seed() {
        String suffix = UUID.randomUUID().toString();
        jdbc.update("""
            insert into user_account(username,email,password_hash,role,status,session_version,created_at,updated_at)
            values (?,?,?,'ADMIN','ACTIVE',0,now(),now())
            """, "preflight-" + suffix, suffix + "@fixture.invalid", suffix);
        user = Objects.requireNonNull(jdbc.queryForObject("select id from user_account where username=?", Long.class, "preflight-" + suffix));
    }
    @AfterEach void removeFixture() {
        jdbc.update("delete from preflight_preview where user_id=?", user);
        jdbc.update("delete from user_account where id=?", user);
    }
    private Grant grant() { return new Grant(user, 0, 1, "fixture/repo", true, Long.MAX_VALUE, UUID.randomUUID().toString()); }
    private String sha() { return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""); }

    @Test void actualCommittedClaimSurvivesNewSessionAndEncryptsSummary() {
        var grant = grant(); var sha = sha();
        assertThat(store.claim(grant, sha, false)).isNull();
        assertThat(jdbc.queryForObject("select status from preflight_preview where user_id=? and request_sha256=?",
            String.class, user, sha)).isEqualTo("RUNNING");
        store.complete(grant, sha, "{\"scope\":\"ONLINE_PREFLIGHT\"}");
        assertThat(mapper.result(1, user, sha)).startsWith("enc:").doesNotContain("ONLINE_PREFLIGHT");
        assertThat(store.claim(grant(), sha, false)).contains("ONLINE_PREFLIGHT");
        assertThat(mapper.countRecent(1, false)).isEqualTo(1);
        assertThat(mapper.exists(2, user, sha)).isZero();
    }

    @Test void exactlyOneConcurrentClaimCommitsBeforeAnyProviderWork() throws Exception {
        var first = grant(); var second = grant(); var sha = sha();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (var grant : List.of(first, second)) results.add(executor.submit(() -> {
                start.await();
                try { return store.claim(grant, sha, true) == null; }
                catch (BusinessException conflict) { return false; }
            }));
            start.countDown();
            int claims = 0;
            for (var result : results) if (result.get(8, TimeUnit.SECONDS)) claims++;
            assertThat(claims).isEqualTo(1);
            assertThat(mapper.exists(1, user, sha)).isEqualTo(1);
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void failedAndRunningClaimsDoNotRetryAndCredentialCannotClaimAnotherPayload() {
        var grant = grant(); var sha = sha();
        store.claim(grant, sha, true);
        assertThatThrownBy(() -> store.claim(grant(), sha, true)).isInstanceOf(BusinessException.class);
        store.fail(grant, sha);
        assertThatThrownBy(() -> store.claim(grant(), sha, true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.claim(grant, sha(), true)).isInstanceOf(BusinessException.class);
        assertThat(mapper.countRecent(1, true)).isEqualTo(1);
    }

    @Test void realDatabaseEnforcesModelDailyCapBeforeSixthClaim() {
        for (int i = 0; i < 5; i++) store.claim(grant(), sha(), true);
        assertThatThrownBy(() -> store.claim(grant(), sha(), true)).isInstanceOf(BusinessException.class);
        assertThat(mapper.countRecent(1, true)).isEqualTo(5);
        assertThat(store.claim(grant(), sha(), false)).isNull();
    }

    @Test void expirationPurgesCiphertextPreservesDedupThenDeletesOnlyExpiredTombstone() {
        var grant = grant(); var sha = sha(); store.claim(grant, sha, false);
        store.complete(grant, sha, "{\"fixture\":true}");
        jdbc.update("update preflight_preview set response_expires_at=utc_timestamp(6)-interval 1 second where user_id=? and request_sha256=?",
            user, sha);
        store.cleanupExpired();
        assertThat(mapper.result(1, user, sha)).isNull(); assertThat(mapper.exists(1, user, sha)).isEqualTo(1);
        assertThatThrownBy(() -> store.claim(grant(), sha, false)).isInstanceOf(BusinessException.class);
        jdbc.update("update preflight_preview set dedup_expires_at=utc_timestamp(6)-interval 1 second where user_id=? and request_sha256=?",
            user, sha);
        store.cleanupExpired(); assertThat(mapper.exists(1, user, sha)).isZero();
    }

    @Configuration
    @EnableTransactionManagement
    static class Fixture {
        @Bean PlatformTransactionManager transactions(DataSource data) { return new DataSourceTransactionManager(data); }
        @Bean SqlSessionFactory factory(DataSource data) throws Exception {
            var configuration = new MybatisConfiguration(); configuration.addMapper(PreflightPreviewMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(data); factory.setConfiguration(configuration); return factory.getObject();
        }
        @Bean PreflightPreviewMapper previews(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(PreflightPreviewMapper.class);
        }
        @Bean SecretCryptoService crypto() { return new SecretCryptoService(UUID.randomUUID().toString()); }
        @Bean PreflightPreviewStore store(PreflightPreviewMapper mapper, SecretCryptoService crypto) {
            return new PreflightPreviewStore(mapper, crypto);
        }
    }
}
