package com.repoguard.agent.review.quality;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmModelBudgetDto;
import com.repoguard.agent.dto.LlmModelReleaseCenterDto;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.dto.LlmModelReleaseRequest;
import com.repoguard.agent.dto.LlmModelRollbackRequest;
import com.repoguard.agent.dto.LlmQualityByModelDto;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped model release control plane. It keeps release evidence aggregate-only and uses a
 * deterministic task-id hash for canary routing, so retries never change a task's assigned model.
 */
@Service
public class LlmModelReleaseService {

    private static final BigDecimal MIN_PRECISION = new BigDecimal("0.90");
    private static final BigDecimal MIN_RECALL = new BigDecimal("0.80");
    private static final BigDecimal MIN_ANCHOR_RATE = new BigDecimal("0.95");
    private static final BigDecimal MAX_DUPLICATE_RATE = new BigDecimal("0.05");
    private static final BigDecimal MAX_PARSE_FAILURE_RATE = new BigDecimal("0.05");
    private static final long MAX_P95_LATENCY_MS = 15_000L;
    private static final int MIN_CANARY_PERCENT = 1;

    private final JdbcTemplate jdbcTemplate;
    private final LlmQualityComparisonProvider qualityComparisonProvider;
    private final LlmModelReleaseRepository releaseRepository;

    public LlmModelReleaseService(
        JdbcTemplate jdbcTemplate,
        LlmQualityComparisonProvider qualityComparisonProvider,
        LlmModelReleaseRepository releaseRepository
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.qualityComparisonProvider = Objects.requireNonNull(qualityComparisonProvider, "qualityComparisonProvider");
        this.releaseRepository = Objects.requireNonNull(releaseRepository, "releaseRepository");
    }

    @Transactional
    public LlmModelReleaseCenterDto getCenter(Integer trendDays) {
        reconcileCanaries();
        int days = normalizeTrendDays(trendDays);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        Map<String, Object> configured = configuredModel(tenantId);
        List<LlmModelReleaseDto> releases = releaseRepository.findAll(tenantId);
        LlmModelReleaseDto active = firstState(releases, "ACTIVE");
        LlmModelReleaseDto canary = firstState(releases, "CANARY");
        var quality = qualityComparisonProvider.getLlmQuality(days);
        List<LlmQualityByModelDto> comparisons = quality == null ? List.of() : quality.byModel();
        LlmModelBudgetDto budget = monthlyBudget(tenantId);
        return new LlmModelReleaseCenterDto(
            text(configured.get("llm_provider")), text(configured.get("model_name")), active, canary, releases,
            comparisons == null ? List.of() : List.copyOf(comparisons), budget, recommendedAction(active, canary, budget)
        );
    }

    @Transactional
    public LlmModelReleaseDto registerShadow(LlmModelReleaseRequest request, String operator) {
        NormalizedRelease normalized = normalize(request, operator);
        return save(normalized, "SHADOW", 0, normalized.operator(), "");
    }

    @Transactional
    public LlmModelReleaseDto promote(LlmModelReleaseRequest request, String operator) {
        NormalizedRelease normalized = normalize(request, operator);
        List<String> blockers = promotionBlockers(normalized);
        if (!blockers.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布未通过质量门禁: " + String.join(", ", blockers));
        }
        int trafficPercent = normalized.trafficPercent();
        String state = trafficPercent == 100 ? "ACTIVE" : "CANARY";
        long tenantId = TenantContext.currentTenantIdOrDefault();
        if ("ACTIVE".equals(state)) releaseRepository.markActiveReplaced(tenantId);
        return save(normalized, state, trafficPercent, normalized.operator(), "");
    }

    @Transactional
    public LlmModelReleaseDto rollback(long releaseId, LlmModelRollbackRequest request, String operator) {
        if (releaseId < 1 || request == null || request.reason() == null || request.reason().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布回滚参数不完整");
        }
        long tenantId = TenantContext.currentTenantIdOrDefault();
        int updated = releaseRepository.rollback(tenantId, releaseId, truncate(request.reason().trim(), 512));
        if (updated != 1) throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布不存在或已回滚");
        return releaseRepository.findById(tenantId, releaseId);
    }

    /** Applies budget protection and persisted canary assignment to one review task. */
    public ReviewPolicySettings route(ReviewPolicySettings settings, ReviewTask task) {
        if (settings == null || task == null || !settings.enabled()) return settings;
        long tenantId = TenantContext.currentTenantIdOrDefault();
        if (monthlyBudget(tenantId).exhausted()) return disableLlm(settings);
        reconcileCanaries();
        List<LlmModelReleaseDto> releases = releaseRepository.findAll(tenantId);
        LlmModelReleaseDto canary = firstState(releases, "CANARY");
        if (canary != null && inCanaryTraffic(task, canary)) return routedSettings(settings, canary);
        LlmModelReleaseDto active = firstState(releases, "ACTIVE");
        return active == null ? settings : routedSettings(settings, active);
    }

    private LlmModelReleaseDto save(
        NormalizedRelease release, String state, int trafficPercent, String operator, String rollbackReason
    ) {
        return releaseRepository.save(
            TenantContext.currentTenantIdOrDefault(), release, state, trafficPercent, operator, rollbackReason
        );
    }

    private ReviewPolicySettings routedSettings(ReviewPolicySettings settings, LlmModelReleaseDto release) {
        if (settings.llmProvider() == null || !settings.llmProvider().equalsIgnoreCase(release.provider())) return settings;
        return new ReviewPolicySettings(
            settings.exists(), settings.llmEnabled(), settings.llmProvider(), release.modelName(), settings.baseUrl(),
            settings.apiKey(), settings.timeoutSeconds(), settings.temperature(), settings.maxTokens(),
            settings.fallbackToRules(), settings.workerConcurrency(), settings.chunkFileThreshold(),
            settings.chunkLineThreshold(), settings.chunkMaxFiles(), settings.chunkMaxLines(),
            settings.inputTokenPricePerMillion(), settings.outputTokenPricePerMillion(), settings.strategyRelease()
        );
    }

    private ReviewPolicySettings disableLlm(ReviewPolicySettings settings) {
        return new ReviewPolicySettings(
            settings.exists(), false, settings.llmProvider(), settings.modelName(), settings.baseUrl(), settings.apiKey(),
            settings.timeoutSeconds(), settings.temperature(), settings.maxTokens(), true, settings.workerConcurrency(),
            settings.chunkFileThreshold(), settings.chunkLineThreshold(), settings.chunkMaxFiles(), settings.chunkMaxLines(),
            settings.inputTokenPricePerMillion(), settings.outputTokenPricePerMillion(), settings.strategyRelease()
        );
    }

    private void reconcileCanaries() {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        List<LlmModelReleaseDto> canaries = releaseRepository.findByState(tenantId, "CANARY");
        if (canaries.isEmpty()) return;
        LlmModelBudgetDto budget = monthlyBudget(tenantId);
        for (LlmModelReleaseDto canary : canaries) {
            List<String> blockers = unsafeRuntimeBlockers(canary, budget);
            if (!blockers.isEmpty()) {
                releaseRepository.rollback(
                    tenantId, canary.id(), truncate("自动回滚: " + String.join(", ", blockers), 512)
                );
            }
        }
    }

    private List<String> unsafeRuntimeBlockers(LlmModelReleaseDto release, LlmModelBudgetDto budget) {
        List<String> blockers = new ArrayList<>();
        if (!Boolean.TRUE.equals(release.qualityGatePassed())) blockers.add("QUALITY_GATE_FAILED");
        if (release.precisionRate().compareTo(MIN_PRECISION) < 0) blockers.add("PRECISION_BELOW_90");
        if (release.recallRate().compareTo(MIN_RECALL) < 0) blockers.add("RECALL_BELOW_80");
        if (release.anchorRate().compareTo(MIN_ANCHOR_RATE) < 0) blockers.add("ANCHOR_RATE_BELOW_95");
        if (release.duplicateRate().compareTo(MAX_DUPLICATE_RATE) > 0) blockers.add("DUPLICATE_RATE_ABOVE_5");
        if (release.parseFailureRate().compareTo(MAX_PARSE_FAILURE_RATE) > 0) blockers.add("PARSE_FAILURE_RATE_ABOVE_5");
        if (release.p95LatencyMs() > MAX_P95_LATENCY_MS) blockers.add("P95_LATENCY_ABOVE_15000_MS");
        if (budget.exhausted()) blockers.add("MONTHLY_LLM_BUDGET_EXHAUSTED");
        return List.copyOf(blockers);
    }

    private List<String> promotionBlockers(NormalizedRelease release) {
        List<String> blockers = new ArrayList<>(release.blockers());
        if (!release.qualityGatePassed()) blockers.add("QUALITY_GATE_FAILED");
        if (release.trafficPercent() < MIN_CANARY_PERCENT) blockers.add("TRAFFIC_PERCENT_MUST_BE_1_TO_100");
        if (release.precisionRate().compareTo(MIN_PRECISION) < 0) blockers.add("PRECISION_BELOW_90");
        if (release.recallRate().compareTo(MIN_RECALL) < 0) blockers.add("RECALL_BELOW_80");
        if (release.anchorRate().compareTo(MIN_ANCHOR_RATE) < 0) blockers.add("ANCHOR_RATE_BELOW_95");
        if (release.duplicateRate().compareTo(MAX_DUPLICATE_RATE) > 0) blockers.add("DUPLICATE_RATE_ABOVE_5");
        if (release.parseFailureRate().compareTo(MAX_PARSE_FAILURE_RATE) > 0) blockers.add("PARSE_FAILURE_RATE_ABOVE_5");
        if (release.p95LatencyMs() > MAX_P95_LATENCY_MS) blockers.add("P95_LATENCY_ABOVE_15000_MS");
        return blockers.stream().distinct().toList();
    }

    private NormalizedRelease normalize(LlmModelReleaseRequest request, String operator) {
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布请求不能为空");
        String fingerprint = request.datasetFingerprint() == null
            ? ""
            : request.datasetFingerprint().trim().toLowerCase(Locale.ROOT);
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "数据集指纹必须是 SHA-256 十六进制值");
        }
        return new NormalizedRelease(
            requireText(request.releaseKey(), "releaseKey"), requireText(request.provider(), "provider"),
            requireText(request.modelName(), "modelName"), requireText(request.promptVersion(), "promptVersion"),
            requireText(request.contextVersion(), "contextVersion"), requireText(request.schemaVersion(), "schemaVersion"),
            requireText(request.datasetId(), "datasetId"), requireText(request.datasetVersion(), "datasetVersion"),
            fingerprint, bounded(request.trafficPercent()), Boolean.TRUE.equals(request.qualityGatePassed()),
            decimal(request.precisionRate()), decimal(request.recallRate()), decimal(request.anchorRate()),
            decimal(request.duplicateRate()), decimal(request.parseFailureRate()), nonNegative(request.p95LatencyMs()),
            decimal(request.averageCost()), nonNegative(request.totalTokens()), normalizeBlockers(request.blockers()),
            normalizeOperator(operator)
        );
    }

    private LlmModelBudgetDto monthlyBudget(long tenantId) {
        YearMonth month = YearMonth.now();
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        List<Map<String, Object>> limits = jdbcTemplate.queryForList(
            "select monthly_llm_token_budget, monthly_llm_cost_budget from tenant_quota_config where tenant_id = ?", tenantId
        );
        long tokenBudget = limits.isEmpty() ? 0L : number(limits.getFirst().get("monthly_llm_token_budget"));
        BigDecimal costBudget = limits.isEmpty() ? BigDecimal.ZERO : decimal(limits.getFirst().get("monthly_llm_cost_budget"));
        List<Map<String, Object>> usage = jdbcTemplate.queryForList(
            """
            select coalesce(sum(llm_total_tokens), 0) as token_used,
                   coalesce(sum(llm_estimated_cost), 0) as cost_used
              from review_task
             where tenant_id = ? and created_at >= ? and created_at < ?
               and llm_status_norm <> '' and llm_status_norm <> 'pending'
            """, tenantId, start, end
        );
        long tokenUsed = usage.isEmpty() ? 0L : number(usage.getFirst().get("token_used"));
        BigDecimal costUsed = usage.isEmpty() ? BigDecimal.ZERO : decimal(usage.getFirst().get("cost_used"));
        long tokenRemaining = tokenBudget <= 0 ? -1L : Math.max(0L, tokenBudget - tokenUsed);
        BigDecimal costRemaining = costBudget.signum() <= 0 ? BigDecimal.valueOf(-1) : costBudget.subtract(costUsed).max(BigDecimal.ZERO);
        return new LlmModelBudgetDto(
            month, tokenBudget, tokenUsed, tokenRemaining, costBudget, costUsed, costRemaining,
            tokenBudget > 0 && tokenUsed >= tokenBudget || costBudget.signum() > 0 && costUsed.compareTo(costBudget) >= 0
        );
    }

    private Map<String, Object> configuredModel(long tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select llm_provider, model_name from review_policy_config where tenant_id = ?", tenantId
        );
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private boolean inCanaryTraffic(ReviewTask task, LlmModelReleaseDto canary) {
        long key = task.getId() == null ? Math.max(1, task.getPrNumber() == null ? 1 : task.getPrNumber()) : task.getId();
        long bucket = Math.floorMod(key * 1103515245L + 12345L, 100L);
        return bucket < canary.trafficPercent();
    }

    private LlmModelReleaseDto firstState(List<LlmModelReleaseDto> releases, String state) {
        return releases.stream().filter(release -> state.equalsIgnoreCase(release.state())).findFirst().orElse(null);
    }

    private String recommendedAction(LlmModelReleaseDto active, LlmModelReleaseDto canary, LlmModelBudgetDto budget) {
        if (budget.exhausted()) return "BUDGET_EXHAUSTED_ROLLBACK_OR_INCREASE_LIMIT";
        if (canary != null) return "OBSERVE_CANARY_QUALITY_AND_COST_BEFORE_FULL_PROMOTION";
        return active == null ? "REGISTER_SHADOW_DATASET_EVALUATION" : "RUN_SHADOW_EVALUATION_FOR_NEXT_VERSION";
    }

    private int normalizeTrendDays(Integer value) {
        return value == null ? 30 : Math.max(7, Math.min(90, value));
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 不能为空");
        return value.trim();
    }

    private String normalizeOperator(String value) {
        return truncate(value == null || value.isBlank() ? "system" : value.trim(), 128);
    }

    private List<String> normalizeBlockers(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
            .map(value -> truncate(value, 128)).distinct().limit(10).toList();
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.max(BigDecimal.ZERO);
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue()).max(BigDecimal.ZERO);
        if (value == null) return BigDecimal.ZERO;
        try { return new BigDecimal(value.toString()).max(BigDecimal.ZERO); } catch (NumberFormatException ex) { return BigDecimal.ZERO; }
    }

    private long number(Object value) {
        if (value instanceof Number number) return Math.max(0L, number.longValue());
        if (value == null) return 0L;
        try { return Math.max(0L, Long.parseLong(value.toString())); } catch (NumberFormatException ex) { return 0L; }
    }

    private int bounded(Integer value) {
        return value == null ? 0 : Math.max(0, Math.min(100, value));
    }

    private long nonNegative(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    record NormalizedRelease(
        String releaseKey, String provider, String modelName, String promptVersion, String contextVersion,
        String schemaVersion, String datasetId, String datasetVersion, String datasetFingerprint, int trafficPercent,
        boolean qualityGatePassed, BigDecimal precisionRate, BigDecimal recallRate, BigDecimal anchorRate,
        BigDecimal duplicateRate, BigDecimal parseFailureRate, long p95LatencyMs, BigDecimal averageCost,
        long totalTokens, List<String> blockers, String operator
    ) {
    }
}
