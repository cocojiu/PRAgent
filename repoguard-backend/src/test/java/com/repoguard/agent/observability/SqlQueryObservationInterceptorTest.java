package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.util.List;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

class SqlQueryObservationInterceptorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RepoGuardMetrics metrics = new RepoGuardMetrics(meterRegistry);
    private final SqlQueryObservationInterceptor interceptor = new SqlQueryObservationInterceptor(metrics);

    @Test
    void recordsSuccessfulFourArgumentQueryRowsAndStatement() throws Throwable {
        Executor target = mock(Executor.class);
        MappedStatement mappedStatement = mappedStatement("DashboardMapper.selectMetricStat");
        List<Object> rows = List.of("a", "b");
        when(target.query(mappedStatement, null, RowBounds.DEFAULT, null)).thenReturn(rows);

        Object result = interceptor.intercept(new Invocation(
            target,
            queryMethod("query"),
            new Object[] {mappedStatement, null, RowBounds.DEFAULT, null}
        ));

        assertThat(result).isEqualTo(rows);
        assertThat(timerCount(
            "repoguard.sql.query.duration",
            "statement", "dashboardmapper.selectmetricstat",
            "command", "select",
            "result", "success"
        )).isEqualTo(1);
        assertThat(summaryTotal(
            "repoguard.sql.query.rows",
            "statement", "dashboardmapper.selectmetricstat",
            "command", "select",
            "result", "success"
        )).isEqualTo(2.0);
    }

    @Test
    void recordsThresholdSignalWhenSqlRowsExceedConfiguredLimit() throws Throwable {
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setSqlRows(2);
        SqlQueryObservationInterceptor thresholdInterceptor = new SqlQueryObservationInterceptor(
            metrics,
            new ObservabilityThresholdMonitor(metrics, properties)
        );
        Executor target = mock(Executor.class);
        MappedStatement mappedStatement = mappedStatement("DashboardMapper.selectMetricStat");
        List<Object> rows = List.of("a", "b");
        when(target.query(mappedStatement, null, RowBounds.DEFAULT, null)).thenReturn(rows);

        thresholdInterceptor.intercept(new Invocation(
            target,
            queryMethod("query"),
            new Object[] {mappedStatement, null, RowBounds.DEFAULT, null}
        ));

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "sql_rows",
            "subject", "dashboardmapper.selectmetricstat"
        )).isEqualTo(1.0);
    }

    @Test
    void recordsSixArgumentQueryInvocation() throws Throwable {
        Executor target = mock(Executor.class);
        MappedStatement mappedStatement = mappedStatement("ReviewTaskMapper.selectPage");
        CacheKey cacheKey = new CacheKey();
        BoundSql boundSql = mock(BoundSql.class);
        List<Object> rows = List.of("only");
        when(target.query(mappedStatement, null, RowBounds.DEFAULT, null, cacheKey, boundSql)).thenReturn(rows);

        Object result = interceptor.intercept(new Invocation(
            target,
            queryMethod("queryWithCache"),
            new Object[] {
                mappedStatement,
                null,
                RowBounds.DEFAULT,
                null,
                cacheKey,
                boundSql
            }
        ));

        assertThat(result).isEqualTo(rows);
        assertThat(timerCount(
            "repoguard.sql.query.duration",
            "statement", "reviewtaskmapper.selectpage",
            "command", "select",
            "result", "success"
        )).isEqualTo(1);
        assertThat(summaryTotal(
            "repoguard.sql.query.rows",
            "statement", "reviewtaskmapper.selectpage",
            "command", "select",
            "result", "success"
        )).isEqualTo(1.0);
    }

    @Test
    void recordsFailedQueryWithoutSwallowingException() throws Exception {
        Executor target = mock(Executor.class);
        MappedStatement mappedStatement = mappedStatement("ReviewFindingMapper.selectList");
        when(target.query(mappedStatement, null, RowBounds.DEFAULT, null))
            .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> interceptor.intercept(new Invocation(
            target,
            queryMethod("query"),
            new Object[] {mappedStatement, null, RowBounds.DEFAULT, null}
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessage("db down");

        assertThat(timerCount(
            "repoguard.sql.query.duration",
            "statement", "reviewfindingmapper.selectlist",
            "command", "select",
            "result", "failed"
        )).isEqualTo(1);
        assertThat(summaryTotal(
            "repoguard.sql.query.rows",
            "statement", "reviewfindingmapper.selectlist",
            "command", "select",
            "result", "failed"
        )).isZero();
    }

    @Test
    void isSpringComponentForMyBatisAutoRegistration() {
        assertThat(SqlQueryObservationInterceptor.class.getAnnotation(Component.class)).isNotNull();
    }

    private MappedStatement mappedStatement(String shortId) {
        MappedStatement mappedStatement = mock(MappedStatement.class);
        when(mappedStatement.getId()).thenReturn("com.repoguard.agent.mapper." + shortId);
        when(mappedStatement.getSqlCommandType()).thenReturn(SqlCommandType.SELECT);
        return mappedStatement;
    }

    private Method queryMethod(String name) throws NoSuchMethodException {
        if ("queryWithCache".equals(name)) {
            return Executor.class.getMethod(
                "query",
                MappedStatement.class,
                Object.class,
                RowBounds.class,
                ResultHandler.class,
                CacheKey.class,
                BoundSql.class
            );
        }
        return Executor.class.getMethod(
            name,
            MappedStatement.class,
            Object.class,
            RowBounds.class,
            ResultHandler.class
        );
    }

    private long timerCount(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).timer().count();
    }

    private double summaryTotal(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).summary().totalAmount();
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter().count();
    }
}
