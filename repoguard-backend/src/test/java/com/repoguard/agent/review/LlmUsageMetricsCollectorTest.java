package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.repoguard.agent.config.RuntimeRoleContract;
import com.repoguard.agent.review.LlmUsageMetricsCollector.PendingSample;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.env.MockEnvironment;

class LlmUsageMetricsCollectorTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Instant now = Instant.parse("2026-10-14T03:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Logger logger = (Logger) LoggerFactory.getLogger(LlmUsageMetricsCollector.class);
    private final ch.qos.logback.classic.Level previousLevel = logger.getLevel();
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    LlmUsageMetricsCollectorTest() {
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        events.setContext(logger.getLoggerContext()); events.start(); logger.addAppender(events);
    }
    @AfterEach
    void detach() { logger.detachAppender(events); events.stop(); logger.setLevel(previousLevel); }
    private LlmUsageMetricsCollector collector(String role, boolean enabled) {
        var environment = new MockEnvironment().withProperty("app.runtime.role", role);
        if (role.equals("worker")) environment.withProperty("app.runtime.api.instance-count", "0");
        return new LlmUsageMetricsCollector(jdbc, registry, RuntimeRoleContract.resolve(environment), enabled, clock);
    }
    @Test
    void failedCollectionRetainsUnknownUsageAndLastSuccessfulEvidence() {
        var collector = collector("api", true);
        when(jdbc.query(any(PreparedStatementCreator.class),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<List<PendingSample>>>any()))
            .thenReturn(List.of(new PendingSample("UNKNOWN", 3, now.minusSeconds(3600))))
            .thenThrow(new DataAccessResourceFailureException("fixture unavailable"));
        assertThat(registry.get("repoguard.llm.usage.pending").tag("status", "unknown").gauge().value()).isNaN();
        collector.collect(); collector.collect();
        assertThat(registry.get("repoguard.llm.usage.pending").tag("status", "unknown").gauge().value()).isEqualTo(3);
        assertThat(registry.get("repoguard.llm.usage.snapshot.last.success").gauge().value()).isEqualTo(now.getEpochSecond());
        assertThat(registry.get("repoguard.llm.usage.snapshot.failed").counter().count()).isEqualTo(1);
    }
    @Test
    void jdbcTimeoutAndUtcEvidenceArePreservedAcrossStatusCaseDifferences() throws Exception {
        Connection connection = mock(Connection.class); PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(rows.next()).thenReturn(true).thenReturn(false);
        when(rows.getString("status")).thenReturn("unknown"); when(rows.getLong("pending_count")).thenReturn(2L);
        when(rows.getObject("oldest", LocalDateTime.class)).thenReturn(LocalDateTime.parse("2026-10-14T02:00:00"));
        when(jdbc.query(any(PreparedStatementCreator.class),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<List<PendingSample>>>any())).thenAnswer(invocation -> {
                PreparedStatementCreator creator = invocation.getArgument(0);
                assertThat(creator.createPreparedStatement(connection)).isSameAs(statement);
                ResultSetExtractor<List<PendingSample>> extractor = invocation.getArgument(1);
                return extractor.extractData(rows);
            });
        var collector = collector("api", true);
        collector.sample(); verify(statement).setQueryTimeout(3);
        assertThat(registry.get("repoguard.llm.usage.pending").tag("status", "unknown").gauge().value()).isEqualTo(2);
        assertThat(registry.get("repoguard.llm.usage.oldest.age").tag("status", "unknown").gauge().value()).isEqualTo(3600);
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
            .contains("operation=runtime_metric_snapshot metric=llm_pending role=api dimension=unknown value=2.0");
    }
    @Test
    void workerEmitsHealthAndCountersWithoutAccountingReadsOrArbitraryLabels() {
        registry.counter("repoguard.llm.budget.denied", "reason", "tenant-secret-17").increment();
        collector("worker", true).sample(); verifyNoInteractions(jdbc);
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(value -> value.contains("metric=heartbeat role=worker"))
            .noneMatch(value -> value.contains("tenant-secret") || value.contains("metric=llm_pending"));
    }
    @Test
    void disabledAccountingStillProvidesHealthWithoutReportingAnUnknownBalanceAsZero() {
        collector("api", false).sample(); verifyNoInteractions(jdbc);
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
            .contains("operation=runtime_metric_snapshot metric=accounting_enabled role=api dimension=all value=0.0")
            .noneMatch(value -> value.contains("metric=llm_pending") || value.contains("NaN"));
    }
    @Test
    void emptyAndFutureDatedSnapshotsHaveCorrectZeroCountsAndNonnegativeAges() {
        var collector = collector("api", false);
        collector.collected(List.of(new PendingSample("LEGACY", 1, now.plusSeconds(30))));
        assertThat(registry.get("repoguard.llm.usage.oldest.age").tag("status", "legacy").gauge().value()).isZero();
        collector.collected(List.of());
        assertThat(registry.get("repoguard.llm.usage.pending").tag("status", "legacy").gauge().value()).isZero();
    }
}
