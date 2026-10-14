package com.repoguard.agent.review;

import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.config.ReplicaRuntimeEnabled;
import com.repoguard.agent.observability.LlmUsageMetricsRecorder;
import com.repoguard.agent.observability.LlmUsageMetricsRecorder.PendingSample;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Host-wide aggregate metrics, collected only by the API process with a bounded query timeout. */
@Component
@ApiRuntimeEnabled
@ReplicaRuntimeEnabled
@ConditionalOnProperty(name = "repoguard.observability.llm-usage-metrics-enabled", havingValue = "true", matchIfMissing = true)
public final class LlmUsageMetricsCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmUsageMetricsCollector.class);
    private final JdbcTemplate jdbc;
    private final LlmUsageMetricsRecorder metrics;

    public LlmUsageMetricsCollector(JdbcTemplate jdbc, LlmUsageMetricsRecorder metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void collect() {
        ResultSetExtractor<List<PendingSample>> extractor = rows -> {
            List<PendingSample> samples = new ArrayList<>();
            while (rows.next()) {
                LocalDateTime oldest = rows.getObject("oldest", LocalDateTime.class);
                samples.add(new PendingSample(rows.getString("status"), rows.getLong("pending_count"),
                    oldest == null ? null : oldest.toInstant(ZoneOffset.UTC)));
            }
            return List.copyOf(samples);
        };
        try {
            metrics.collected(jdbc.query(connection -> {
                var statement = connection.prepareStatement("select status,count(*) as pending_count,min(started_at) as oldest "
                    + "from llm_usage_call where " + LlmUsagePendingQuery.PENDING_PREDICATE + " group by status");
                statement.setQueryTimeout(3);
                return statement;
            }, extractor));
        } catch (org.springframework.dao.DataAccessException unavailable) {
            metrics.collectionFailed();
            LOGGER.warn("LLM usage metrics collection failed category=store_unavailable");
        }
    }
}
