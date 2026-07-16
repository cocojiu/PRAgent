package com.repoguard.agent.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

@Component
public class FlywayV48SqlModeCustomizer implements FlywayConfigurationCustomizer {

    static final String COMPATIBILITY_SQL =
        "SET SESSION sql_mode = REPLACE(@@SESSION.sql_mode, 'ONLY_FULL_GROUP_BY', '')";

    private final DataSourceProperties dataSourceProperties;

    public FlywayV48SqlModeCustomizer(DataSourceProperties dataSourceProperties) {
        this.dataSourceProperties = dataSourceProperties;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        configuration.dataSource(
            dataSourceProperties.determineUrl(),
            dataSourceProperties.determineUsername(),
            dataSourceProperties.determinePassword()
        );
        configuration.initSql(COMPATIBILITY_SQL);
    }
}
