package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlywayV48SqlModeCallbackTest {

    private final Connection connection = mock(Connection.class);
    private final Statement query = mock(Statement.class);
    private final ResultSet resultSet = mock(ResultSet.class);
    private final PreparedStatement update = mock(PreparedStatement.class);
    private final Context context = mock(Context.class);
    private final FlywayV48SqlModeCallback callback = new FlywayV48SqlModeCallback();

    @BeforeEach
    void setUp() throws Exception {
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(query);
        when(query.executeQuery("select @@session.sql_mode")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES");
        when(connection.prepareStatement("set session sql_mode = ?")).thenReturn(update);
    }

    @Test
    void relaxesStrictGroupingForMigrationSessionAndRestoresIt() throws Exception {
        assertThat(callback.supports(Event.BEFORE_MIGRATE, context)).isTrue();
        callback.handle(Event.BEFORE_MIGRATE, context);
        verify(update).setString(1, "STRICT_TRANS_TABLES");

        callback.handle(Event.AFTER_MIGRATE, context);
        verify(update).setString(1, "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES");
    }

    @Test
    void restoresSqlModeAfterMigrationError() throws Exception {
        callback.handle(Event.BEFORE_MIGRATE, context);
        callback.handle(Event.AFTER_MIGRATE_ERROR, context);

        verify(update).setString(1, "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES");
    }

    @Test
    void declaresSupportedEventsDuringFlywayCallbackPreparation() {
        assertThat(callback.supports(Event.BEFORE_MIGRATE, null)).isTrue();
        assertThat(callback.supports(Event.AFTER_MIGRATE, null)).isTrue();
        assertThat(callback.supports(Event.AFTER_MIGRATE_ERROR, null)).isTrue();
        assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, null)).isFalse();
        assertThat(callback.supports(Event.BEFORE_VALIDATE, null)).isFalse();
    }

    @Test
    void explicitlyRegistersItselfWithFlyway() {
        FluentConfiguration configuration = mock(FluentConfiguration.class);

        callback.customize(configuration);

        verify(configuration).callbacks(callback);
    }
}
