package com.repoguard.agent.config;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

class FlywayV48SqlModeCustomizerTest {

    @Test
    void configuresDedicatedFlywayConnectionsWithCompatibleSessionSqlMode() {
        DataSourceProperties dataSourceProperties = mock(DataSourceProperties.class);
        FluentConfiguration configuration = mock(FluentConfiguration.class);
        when(dataSourceProperties.determineUrl()).thenReturn("jdbc:mysql://db:3306/repoguard");
        when(dataSourceProperties.determineUsername()).thenReturn("repoguard");
        when(dataSourceProperties.determinePassword()).thenReturn("secret");
        FlywayV48SqlModeCustomizer customizer = new FlywayV48SqlModeCustomizer(dataSourceProperties);

        customizer.customize(configuration);

        InOrder configurationOrder = inOrder(configuration);
        configurationOrder.verify(configuration)
            .dataSource("jdbc:mysql://db:3306/repoguard", "repoguard", "secret");
        configurationOrder.verify(configuration).initSql(FlywayV48SqlModeCustomizer.COMPATIBILITY_SQL);
    }
}
