package com.repoguard.agent.security;

import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.tenancy.ScheduledJobLeaseContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@SchedulerRuntimeEnabled
@ConditionalOnProperty(name = "app.security.rate-limit-store", havingValue = "database")
public class DatabaseRateLimitCleanupBatchExecutor {
    private final JdbcTemplate jdbc;

    public DatabaseRateLimitCleanupBatchExecutor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 2)
    public int deleteExpired(long cutoffMinute, int limit) {
        if (limit < 1 || limit > 5_000) throw new IllegalArgumentException("Invalid cleanup batch limit");
        ScheduledJobLeaseContext.assertHeld();
        int deleted = jdbc.update(connection -> {
            var statement = connection.prepareStatement("DELETE FROM api_rate_limit_window "
                + "WHERE window_epoch_minute < ? ORDER BY window_epoch_minute LIMIT ?");
            statement.setQueryTimeout(2);
            statement.setLong(1, cutoffMinute);
            statement.setInt(2, limit);
            return statement;
        });
        ScheduledJobLeaseContext.assertHeld();
        return deleted;
    }
}
