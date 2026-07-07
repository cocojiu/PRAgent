package com.repoguard.agent.observability;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Intercepts({
    @Signature(
        type = Executor.class,
        method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}
    ),
    @Signature(
        type = Executor.class,
        method = "query",
        args = {
            MappedStatement.class,
            Object.class,
            RowBounds.class,
            ResultHandler.class,
            CacheKey.class,
            BoundSql.class
        }
    )
})
public class SqlQueryObservationInterceptor implements Interceptor {

    private static final String MAPPER_PREFIX = "com.repoguard.agent.mapper.";
    private static final String UNKNOWN = "unknown";

    private final RepoGuardMetrics metrics;
    private final ObservabilityThresholdMonitor thresholdMonitor;

    @Autowired
    public SqlQueryObservationInterceptor(RepoGuardMetrics metrics, ObservabilityThresholdMonitor thresholdMonitor) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.thresholdMonitor = Objects.requireNonNull(thresholdMonitor, "thresholdMonitor");
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long startNanos = System.nanoTime();
        MappedStatement mappedStatement = mappedStatement(invocation);
        Object result = null;
        Throwable failure = null;
        try {
            result = invocation.proceed();
            return result;
        } catch (InvocationTargetException ex) {
            failure = ex.getTargetException();
            throw failure;
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            String statement = statementId(mappedStatement);
            long rows = rowCount(result);
            metrics.sqlQuery(
                duration,
                statement,
                command(mappedStatement),
                failure == null ? "success" : "failed",
                rows
            );
            thresholdMonitor.sqlQuery(
                duration,
                statement,
                command(mappedStatement),
                failure == null ? "success" : "failed",
                rows
            );
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // No runtime properties are required.
    }

    private MappedStatement mappedStatement(Invocation invocation) {
        Object[] args = invocation.getArgs();
        if (args == null || args.length == 0 || !(args[0] instanceof MappedStatement mappedStatement)) {
            return null;
        }
        return mappedStatement;
    }

    private String statementId(MappedStatement mappedStatement) {
        if (mappedStatement == null || !StringUtils.hasText(mappedStatement.getId())) {
            return UNKNOWN;
        }
        String statementId = mappedStatement.getId();
        return statementId.startsWith(MAPPER_PREFIX) ? statementId.substring(MAPPER_PREFIX.length()) : statementId;
    }

    private String command(MappedStatement mappedStatement) {
        if (mappedStatement == null) {
            return UNKNOWN;
        }
        SqlCommandType commandType = mappedStatement.getSqlCommandType();
        return commandType == null ? UNKNOWN : commandType.name();
    }

    private long rowCount(Object result) {
        if (result instanceof List<?> rows) {
            return rows.size();
        }
        return result == null ? 0L : 1L;
    }
}
