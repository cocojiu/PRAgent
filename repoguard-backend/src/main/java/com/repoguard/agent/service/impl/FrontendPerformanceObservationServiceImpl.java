package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.FrontendApiWaterfallItemDto;
import com.repoguard.agent.dto.FrontendLongTaskItemDto;
import com.repoguard.agent.dto.FrontendPerformanceReportRequest;
import com.repoguard.agent.observability.ObservabilityThresholdMonitor;
import com.repoguard.agent.observability.ObservabilityThresholdProperties;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.service.FrontendPerformanceObservationService;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FrontendPerformanceObservationServiceImpl implements FrontendPerformanceObservationService {

    private static final Logger log = LoggerFactory.getLogger(FrontendPerformanceObservationServiceImpl.class);
    private static final int MAX_API_REQUESTS = 50;
    private static final int MAX_LONG_TASKS = 50;
    private static final int MAX_WATERFALL_LOG_ITEMS = 12;
    private static final int MAX_TEXT_LENGTH = 80;
    private static final String UNKNOWN = "unknown";

    private final RepoGuardMetrics metrics;
    private final ObservabilityThresholdMonitor thresholdMonitor;

    public FrontendPerformanceObservationServiceImpl(RepoGuardMetrics metrics) {
        this(metrics, new ObservabilityThresholdMonitor(metrics, new ObservabilityThresholdProperties()));
    }

    @Autowired
    public FrontendPerformanceObservationServiceImpl(
        RepoGuardMetrics metrics,
        ObservabilityThresholdMonitor thresholdMonitor
    ) {
        this.metrics = metrics;
        this.thresholdMonitor = thresholdMonitor;
    }

    @Override
    public void record(FrontendPerformanceReportRequest request) {
        String route = normalizeRoute(request == null ? null : request.route());
        List<FrontendApiWaterfallItemDto> apiRequests = limited(request == null ? null : request.apiRequests(), MAX_API_REQUESTS);
        List<FrontendLongTaskItemDto> longTasks = limited(request == null ? null : request.longTasks(), MAX_LONG_TASKS);

        apiRequests.forEach(item -> {
            Duration duration = duration(item.durationMs());
            metrics.frontendApiWaterfallRequest(
                duration,
                route,
                item.operation(),
                item.path(),
                item.method(),
                status(item.status()),
                item.result()
            );
            thresholdMonitor.frontendApiRequest(duration, route);
        });
        longTasks.forEach(item -> {
            Duration duration = duration(item.durationMs());
            metrics.frontendLongTask(duration, route);
            thresholdMonitor.frontendLongTask(duration, route);
        });
        if (!apiRequests.isEmpty() || !longTasks.isEmpty()) {
            logObservation(route, apiRequests, longTasks);
        }
    }

    private void logObservation(
        String route,
        List<FrontendApiWaterfallItemDto> apiRequests,
        List<FrontendLongTaskItemDto> longTasks
    ) {
        long apiTotalDuration = apiRequests.stream()
            .mapToLong(item -> safeMillis(item.durationMs()))
            .sum();
        long maxLongTaskDuration = longTasks.stream()
            .mapToLong(item -> safeMillis(item.durationMs()))
            .max()
            .orElse(0);
        String waterfall = apiRequests.stream()
            .sorted(Comparator.comparingLong(item -> safeMillis(item.startedAtMs())))
            .limit(MAX_WATERFALL_LOG_ITEMS)
            .map(item -> safeText(item.operation()) + "@" + safeText(item.path()) + ":" + safeMillis(item.durationMs()) + "ms")
            .collect(Collectors.joining(","));

        log.info(
            "Frontend performance observed route={} apiRequests={} apiTotalDurationMs={} longTasks={} maxLongTaskMs={} waterfall={}",
            route,
            apiRequests.size(),
            apiTotalDuration,
            longTasks.size(),
            maxLongTaskDuration,
            waterfall
        );
    }

    private static <T> List<T> limited(List<T> items, int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
            .filter(Objects::nonNull)
            .limit(limit)
            .toList();
    }

    private static Duration duration(Long millis) {
        return Duration.ofMillis(safeMillis(millis));
    }

    private static long safeMillis(Long millis) {
        if (millis == null || millis < 0) {
            return 0;
        }
        return millis;
    }

    private static String status(Integer status) {
        if (status == null || status <= 0) {
            return UNKNOWN;
        }
        return status.toString();
    }

    private static String normalizeRoute(String route) {
        return safeText(route);
    }

    private static String safeText(String value) {
        if (!StringUtils.hasText(value)) {
            return UNKNOWN;
        }
        String normalized = value.trim().replaceAll("\\s+", "_");
        return normalized.length() > MAX_TEXT_LENGTH ? normalized.substring(0, MAX_TEXT_LENGTH) : normalized;
    }
}
