package com.repoguard.agent.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fails fast when a role that does not own Flyway migrations boots against a
 * schema older than the packaged migration chain expects.
 *
 * <p>Only the migration owner runs Flyway, so every other role would otherwise
 * start silently against whatever schema happens to be present. This guard runs
 * during bean initialisation, before the AMQP listener containers start
 * consuming, so a stale worker never processes a task.
 */
@Component
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "false")
public class SchemaVersionGuard implements InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaVersionGuard.class);
    private static final String HISTORY_TABLE = "flyway_schema_history";

    private final JdbcTemplate jdbcTemplate;
    private final int expectedVersion;

    @Autowired
    public SchemaVersionGuard(DataSource dataSource, SchemaVersionProperties properties) {
        this(new JdbcTemplate(dataSource), properties.getExpectedVersion());
    }

    SchemaVersionGuard(JdbcTemplate jdbcTemplate, int expectedVersion) {
        this.jdbcTemplate = jdbcTemplate;
        this.expectedVersion = expectedVersion;
    }

    @Override
    public void afterPropertiesSet() {
        boolean historyTableExists = historyTableExists();
        // Do not query a table that we have just established is absent. Besides
        // producing a less useful SQL exception, that would hide the operator
        // action in the fail-fast message.
        Integer appliedVersion = historyTableExists ? appliedVersion() : null;
        verify(historyTableExists, appliedVersion);
    }

    /**
     * Applies the boot decision. Split from the queries so the decision can be
     * tested without a database.
     */
    void verify(boolean historyTableExists, Integer appliedVersion) {
        if (!historyTableExists) {
            throw new IllegalStateException(
                "Flyway history table " + HISTORY_TABLE + " is absent; this role does not own migrations, "
                    + "so the migration owner must complete startup before it can boot"
            );
        }
        if (appliedVersion == null) {
            throw new IllegalStateException(
                "No successful Flyway migration found in " + HISTORY_TABLE
                    + "; the migration owner must complete startup before this role can boot"
            );
        }
        if (appliedVersion < expectedVersion) {
            throw new IllegalStateException(
                "Schema version " + appliedVersion + " is older than the required " + expectedVersion
                    + "; deploy the migration owner first so this role does not run against a stale schema"
            );
        }

        LOGGER.info(
            "Schema version guard passed appliedVersion={} expectedVersion={}",
            appliedVersion,
            expectedVersion
        );
    }

    private boolean historyTableExists() {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables "
                + "where table_schema = database() and table_name = ?",
            Integer.class,
            HISTORY_TABLE
        );
        return count != null && count > 0;
    }

    /**
     * Reads the highest successfully applied numeric version. The regexp filter
     * keeps a future dotted version from silently casting to zero and masking a
     * stale schema.
     */
    private Integer appliedVersion() {
        return jdbcTemplate.queryForObject(
            "select max(cast(version as unsigned)) from " + HISTORY_TABLE
                + " where success = 1 and version regexp '^[0-9]+$'",
            Integer.class
        );
    }
}
