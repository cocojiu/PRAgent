package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.repoguard.agent.observability.LlmUsageMetricsRecorder;
import com.repoguard.agent.observability.LlmUsageMetricsRecorder.PendingSample;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

class LlmUsageMetricsCollectorTest {
    @Test
    void collectorRunsOnApiReplicasAndCanBeDisabledWithoutAffectingWorkers() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(LlmUsageMetricsCollector.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(LlmUsageMetricsRecorder.class, () -> new LlmUsageMetricsRecorder(new SimpleMeterRegistry()));
        runner.withPropertyValues("app.runtime.role=api").run(context ->
            assertThat(context).hasSingleBean(LlmUsageMetricsCollector.class));
        runner.withPropertyValues("app.runtime.role=worker", "app.runtime.api.instance-count=0").run(context ->
            assertThat(context).doesNotHaveBean(LlmUsageMetricsCollector.class));
        runner.withPropertyValues("app.runtime.role=api", "repoguard.observability.llm-usage-metrics-enabled=false").run(context ->
            assertThat(context).doesNotHaveBean(LlmUsageMetricsCollector.class));
    }

    @Test
    void successfulCollectionBoundsJdbcTimeAndPublishesUtcStatusEvidence() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(rows.next()).thenReturn(true).thenReturn(false);
        when(rows.getString("status")).thenReturn("UNKNOWN");
        when(rows.getLong("pending_count")).thenReturn(2L);
        when(rows.getObject("oldest", LocalDateTime.class)).thenReturn(LocalDateTime.parse("2026-10-14T00:00:00.123456"));
        when(jdbc.query(any(PreparedStatementCreator.class),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<List<PendingSample>>>any())).thenAnswer(invocation -> {
                PreparedStatementCreator creator = invocation.getArgument(0);
                assertThat(creator.createPreparedStatement(connection)).isSameAs(statement);
                ResultSetExtractor<List<PendingSample>> extractor = invocation.getArgument(1);
                return extractor.extractData(rows);
            });
        var registry = new SimpleMeterRegistry();
        new LlmUsageMetricsCollector(jdbc, new LlmUsageMetricsRecorder(registry)).collect();
        verify(statement).setQueryTimeout(3);
        assertThat(registry.get("repoguard.llm.usage.pending").tag("status", "unknown").gauge().value()).isEqualTo(2);
        assertThat(registry.get("repoguard.llm.usage.snapshot.last.success").gauge().value()).isPositive();
    }

    @Test
    void failureDoesNotPresentUnknownUsageAsZeroOrStopOtherScheduledJobs() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmUsageMetricsRecorder metrics = new LlmUsageMetricsRecorder(registry);
        var collector = new LlmUsageMetricsCollector(jdbc, metrics);
        when(jdbc.query(any(PreparedStatementCreator.class),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<List<PendingSample>>>any()))
            .thenReturn(List.of(new PendingSample("UNKNOWN", 3, Instant.now().minusSeconds(60))))
            .thenThrow(new DataAccessResourceFailureException("fixture unavailable"));
        collector.collect();
        collector.collect();
        assertThat(registry.get("repoguard.llm.usage.pending").tag("status", "unknown").gauge().value()).isEqualTo(3);
        assertThat(registry.get("repoguard.llm.usage.snapshot.failed").counter().count()).isEqualTo(1);
    }
}
