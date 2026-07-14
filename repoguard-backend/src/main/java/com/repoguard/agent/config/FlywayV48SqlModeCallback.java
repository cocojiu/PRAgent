package com.repoguard.agent.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

@Component
public class FlywayV48SqlModeCallback implements Callback {

    private static final String LEGACY_VERSION = "48";
    private static final String STRICT_GROUPING_MODE = "ONLY_FULL_GROUP_BY";

    private final Map<Connection, String> originalSqlModes = Collections.synchronizedMap(new IdentityHashMap<>());

    @Override
    public boolean supports(Event event, Context context) {
        boolean supportedEvent = event == Event.BEFORE_EACH_MIGRATE
            || event == Event.AFTER_EACH_MIGRATE
            || event == Event.AFTER_EACH_MIGRATE_ERROR;
        return supportedEvent && (context == null || isLegacyMigration(context));
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (context == null || !isLegacyMigration(context)) {
            return;
        }
        if (event == Event.BEFORE_EACH_MIGRATE) {
            relaxLegacyMigrationSqlMode(context.getConnection());
            return;
        }
        restoreSqlMode(context.getConnection());
    }

    @Override
    public String getCallbackName() {
        return "v48-only-full-group-by-compatibility";
    }

    private void relaxLegacyMigrationSqlMode(Connection connection) {
        String original = readSqlMode(connection);
        originalSqlModes.put(connection, original);
        String compatible = Arrays.stream(original.split(","))
            .map(String::trim)
            .filter(mode -> !mode.equalsIgnoreCase(STRICT_GROUPING_MODE))
            .collect(Collectors.joining(","));
        if (!compatible.equals(original)) {
            writeSqlMode(connection, compatible);
        }
    }

    private void restoreSqlMode(Connection connection) {
        String original = originalSqlModes.remove(connection);
        if (original != null) {
            writeSqlMode(connection, original);
        }
    }

    private String readSqlMode(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select @@session.sql_mode")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Unable to read Flyway session sql_mode");
            }
            return resultSet.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read Flyway session sql_mode", exception);
        }
    }

    private void writeSqlMode(Connection connection, String sqlMode) {
        try (PreparedStatement statement = connection.prepareStatement("set session sql_mode = ?")) {
            statement.setString(1, sqlMode);
            statement.execute();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to update Flyway session sql_mode", exception);
        }
    }

    private boolean isLegacyMigration(Context context) {
        MigrationInfo migrationInfo = context.getMigrationInfo();
        return migrationInfo != null
            && migrationInfo.getVersion() != null
            && LEGACY_VERSION.equals(migrationInfo.getVersion().getVersion());
    }
}
