package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Rejects all databases except the owned loopback fixture or a GitHub-hosted CI service. */
public final class PreflightIntegrationDatabase {
    private PreflightIntegrationDatabase() { }

    public static DataSource migrateFixture() {
        String local = System.getenv("REPOGUARD_PREFLIGHT_MYSQL_URL");
        boolean ci = local == null;
        String url = ci ? System.getenv("SPRING_DATASOURCE_URL") : local;
        if (ci) {
            assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
            assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
            assertThat(System.getenv("GITHUB_RUN_ID")).matches("[0-9]+");
            assertThat(System.getProperty("os.name")).startsWith("Linux");
            assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:3306/repoguard_integration(?:[?].*)?");
        } else {
            assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:33081/repoguard_preflight_test(?:[?].*)?");
        }
        var source = new DriverManagerDataSource(url, ci ? System.getenv("SPRING_DATASOURCE_USERNAME") : "root",
            ci ? System.getenv("SPRING_DATASOURCE_PASSWORD") : "");
        var jdbc = new JdbcTemplate(source);
        var identity = jdbc.queryForMap("select @@port as port,@@datadir as dir,@@bind_address as bindAddress,database() as db");
        assertThat(((Number) identity.get("port")).intValue()).isEqualTo(ci ? 3306 : 33081);
        assertThat(identity.get("db")).isEqualTo(ci ? "repoguard_integration" : "repoguard_preflight_test");
        String directory = identity.get("dir").toString().replace('\\', '/');
        if (ci) {
            assertThat(directory).isEqualTo("/var/lib/mysql/");
        } else {
            assertThat(identity.get("bindAddress")).isEqualTo("127.0.0.1");
            assertThat(directory).endsWith("/.tmp/mysql-preflight-20261011/data/");
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .initSql("SET SESSION sql_mode = REPLACE(@@SESSION.sql_mode, 'ONLY_FULL_GROUP_BY', '')")
            .cleanDisabled(true).load().migrate();
        assertThat(jdbc.queryForObject("select version from flyway_schema_history where success=1 order by installed_rank desc limit 1",
            String.class)).isEqualTo("104");
        return source;
    }
}
