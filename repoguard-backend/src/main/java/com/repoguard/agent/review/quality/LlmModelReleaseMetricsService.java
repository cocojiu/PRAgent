package com.repoguard.agent.review.quality;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.dto.LlmModelReleaseMetricDto;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Collects bounded runtime evidence and applies explainable canary protection. */
@Service
public class LlmModelReleaseMetricsService {

    static final int MIN_RUNTIME_SAMPLES = 10;
    private static final BigDecimal MAX_PARSE_FAILURE_RATE = new BigDecimal("0.05");
    private static final BigDecimal MAX_FALLBACK_RATE = new BigDecimal("0.20");
    private static final BigDecimal COST_MULTIPLIER = new BigDecimal("1.50");
    private static final long MAX_P95_LATENCY_MS = 15_000L;

    private final LlmModelReleaseRepository releaseRepository;
    private final LlmModelReleaseMetricsRepository metricsRepository;
    private final RepoGuardMetrics metrics;
    private final LlmModelReleaseRuntimeSupport runtimeSupport;
    private final LlmModelReleaseAlertPublisher alertPublisher;

    public LlmModelReleaseMetricsService(
        LlmModelReleaseRepository releaseRepository,
        LlmModelReleaseMetricsRepository metricsRepository,
        RepoGuardMetrics metrics,
        LlmModelReleaseRuntimeSupport runtimeSupport,
        LlmModelReleaseAlertPublisher alertPublisher
    ) {
        this.releaseRepository = Objects.requireNonNull(releaseRepository, "releaseRepository");
        this.metricsRepository = Objects.requireNonNull(metricsRepository, "metricsRepository");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.runtimeSupport = Objects.requireNonNull(runtimeSupport, "runtimeSupport");
        this.alertPublisher = Objects.requireNonNull(alertPublisher, "alertPublisher");
    }

    /** Collects the last completed UTC-local hour; repeated calls upsert the same window. */
    @Transactional
    public List<LlmModelReleaseMetricDto> collectCurrentWindow() {
        LocalDateTime end = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        return collectWindow(end);
    }

    /** Collects the current window before serving the bounded history query. */
    @Transactional
    public List<LlmModelReleaseMetricDto> collectAndList(String releaseKey, int days, int limit) {
        collectCurrentWindow();
        return list(releaseKey, days, limit);
    }

    @Transactional
    public List<LlmModelReleaseMetricDto> collectWindow(LocalDateTime windowEnd) {
        LocalDateTime end = normalizeWindowEnd(windowEnd);
        LocalDateTime start = end.minusHours(1);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        List<LlmModelReleaseMetricDto> result = new ArrayList<>();
        for (LlmModelReleaseDto release : releaseRepository.findAll(tenantId)) {
            result.add(toDto(collectOne(tenantId, release, start, end)));
        }
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public List<LlmModelReleaseMetricDto> list(String releaseKey, int days, int limit) {
        String normalizedKey = normalizeKey(releaseKey);
        if (normalizedKey != null && releaseRepository.findByReleaseKey(TenantContext.currentTenantIdOrDefault(), normalizedKey) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布版本不存在");
        }
        LocalDateTime start = LocalDateTime.now().minusDays(Math.max(1, Math.min(90, days)));
        return metricsRepository.findSnapshots(TenantContext.currentTenantIdOrDefault(), normalizedKey, start, limit)
            .stream().map(this::toDto).toList();
    }

    private LlmModelReleaseMetricSnapshot collectOne(long tenantId, LlmModelReleaseDto release,
        LocalDateTime start, LocalDateTime end) {
        LlmModelReleaseMetricsRepository.RuntimeAggregate aggregate = metricsRepository.aggregate(
            tenantId, release.releaseKey(), start, end);
        long rollbackCount = metricsRepository.countRollbacks(tenantId, release.id(), start, end);
        Decision decision = decide(release, aggregate, rollbackCount);
        LlmModelReleaseMetricSnapshot before = metricsRepository.findOne(release.id(), start, end);
        LlmModelReleaseMetricSnapshot candidate = new LlmModelReleaseMetricSnapshot(
            null, release.id(), release.releaseKey(), release.provider(), release.modelName(), start, end,
            aggregate.sampleCount(), aggregate.totalTokens(), aggregate.totalCost(), aggregate.p95LatencyMs(),
            aggregate.parseFailureCount(), aggregate.fallbackCount(), rollbackCount, decision.state(),
            decision.codes(), decision.action(), fingerprint(release, start, end, decision), null, null
        );
        LlmModelReleaseMetricSnapshot saved = metricsRepository.upsert(candidate);
        metrics.llmReleaseSnapshot(saved.releaseKey(), saved.provider(), saved.modelName(), saved.sampleCount(),
            saved.totalTokens(), saved.totalCost(), saved.p95LatencyMs(), rate(saved.parseFailureCount(), saved.sampleCount()),
            rate(saved.fallbackCount(), saved.sampleCount()), saved.alertState());
        if (decision.alert() && (before == null || !Objects.equals(before.alertFingerprint(), saved.alertFingerprint()))) {
            metrics.llmReleaseAlert(saved.releaseKey(), saved.alertCodes().isEmpty() ? "runtime_threshold" : saved.alertCodes().getFirst(), saved.action());
            alertPublisher.publish(saved);
        }
        if (decision.alert() && "CANARY".equalsIgnoreCase(release.state())) {
            autoRollback(tenantId, release, saved);
        }
        return saved;
    }

    private void autoRollback(long tenantId, LlmModelReleaseDto release, LlmModelReleaseMetricSnapshot snapshot) {
        if (!"AUTO_ROLLBACK".equals(snapshot.action())) return;
        int updated = releaseRepository.rollback(tenantId, release.id(),
            "运行指标自动回滚: " + String.join(", ", snapshot.alertCodes()));
        if (updated == 1) {
            runtimeSupport.audit(tenantId, "AUTO_ROLLBACK", release,
                releaseRepository.findById(tenantId, release.id()), "system", snapshot.alertCodes().toString());
        }
    }

    private Decision decide(LlmModelReleaseDto release,
        LlmModelReleaseMetricsRepository.RuntimeAggregate aggregate, long rollbackCount) {
        if (aggregate.sampleCount() < MIN_RUNTIME_SAMPLES) {
            return new Decision("INSUFFICIENT_SAMPLE", List.of("SAMPLE_COUNT_BELOW_10"), "NONE", false);
        }
        List<String> codes = new ArrayList<>();
        BigDecimal parseRate = rate(aggregate.parseFailureCount(), aggregate.sampleCount());
        BigDecimal fallbackRate = rate(aggregate.fallbackCount(), aggregate.sampleCount());
        if (parseRate.compareTo(MAX_PARSE_FAILURE_RATE) > 0) codes.add("PARSE_FAILURE_RATE_ABOVE_5");
        if (fallbackRate.compareTo(MAX_FALLBACK_RATE) > 0) codes.add("FALLBACK_RATE_ABOVE_20");
        long latencyThreshold = Math.max(MAX_P95_LATENCY_MS, release.p95LatencyMs() == null ? 0L : release.p95LatencyMs() * 3L / 2L);
        if (aggregate.p95LatencyMs() > latencyThreshold) codes.add("P95_LATENCY_ABOVE_RUNTIME_THRESHOLD");
        if (release.averageCost() != null && release.averageCost().signum() > 0) {
            BigDecimal averageCost = aggregate.totalCost().divide(BigDecimal.valueOf(aggregate.sampleCount()), 8, RoundingMode.HALF_UP);
            if (averageCost.compareTo(release.averageCost().multiply(COST_MULTIPLIER)) > 0) codes.add("COST_ABOVE_150_PERCENT");
        }
        if (rollbackCount > 0) codes.add("ROLLBACK_OBSERVED");
        if (codes.isEmpty()) return new Decision("NORMAL", List.of(), "NONE", false);
        boolean autoRollback = "CANARY".equalsIgnoreCase(release.state());
        return new Decision(autoRollback ? "AUTO_ROLLBACK" : "ALERT", List.copyOf(codes), autoRollback ? "AUTO_ROLLBACK" : "NOTIFY", true);
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP);
    }

    private String fingerprint(LlmModelReleaseDto release, LocalDateTime start, LocalDateTime end, Decision decision) {
        String value = release.releaseKey() + "|" + start + "|" + end + "|" + decision.state() + "|" + decision.codes();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private LocalDateTime normalizeWindowEnd(LocalDateTime value) {
        LocalDateTime end = value == null ? LocalDateTime.now() : value;
        return end.truncatedTo(ChronoUnit.HOURS);
    }

    private String normalizeKey(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private LlmModelReleaseMetricDto toDto(LlmModelReleaseMetricSnapshot snapshot) {
        return new LlmModelReleaseMetricDto(snapshot.id(), snapshot.releaseId(), snapshot.releaseKey(), snapshot.provider(),
            snapshot.modelName(), snapshot.windowStart(), snapshot.windowEnd(), snapshot.sampleCount(), snapshot.totalTokens(),
            snapshot.totalCost(), snapshot.p95LatencyMs(), snapshot.parseFailureCount(), snapshot.fallbackCount(),
            snapshot.rollbackCount(), snapshot.alertState(), snapshot.alertCodes(), snapshot.action(), snapshot.alertFingerprint(),
            snapshot.createdAt(), snapshot.updatedAt());
    }

    record Decision(String state, List<String> codes, String action, boolean alert) {
    }
}
