package com.repoguard.agent.review.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmModelBudgetDto;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewPolicySettings;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Runtime routing, budget protection, and append-only release transition evidence. */
@Component
final class LlmModelReleaseRuntimeSupport {

    private final JdbcTemplate jdbcTemplate;
    private final LlmModelReleaseRepository releaseRepository;
    private final ObjectMapper objectMapper;

    LlmModelReleaseRuntimeSupport(JdbcTemplate jdbcTemplate, LlmModelReleaseRepository releaseRepository,
        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.releaseRepository = releaseRepository;
        this.objectMapper = objectMapper;
    }

    LlmModelBudgetDto monthlyBudget(long tenantId) {
        YearMonth month = YearMonth.now(java.time.Clock.systemUTC());
        List<Map<String, Object>> limits = jdbcTemplate.queryForList(
            "select monthly_llm_token_budget, monthly_llm_cost_budget from tenant_quota_config where tenant_id = ?",
            tenantId);
        long tokenBudget = limits.isEmpty() ? 0L : number(limits.getFirst().get("monthly_llm_token_budget"));
        BigDecimal costBudget = limits.isEmpty() ? BigDecimal.ZERO
            : decimal(limits.getFirst().get("monthly_llm_cost_budget"));
        List<Map<String, Object>> usage = jdbcTemplate.queryForList(
            "select accounted_tokens as token_used, accounted_cost as cost_used, unpriced_calls,legacy_unknown_attempts "
                + "from llm_usage_month where tenant_id = ? and usage_month = ?",
            tenantId, month.atDay(1));
        long tokenUsed = usage.isEmpty() ? 0L : number(usage.getFirst().get("token_used"));
        BigDecimal costUsed = usage.isEmpty() ? BigDecimal.ZERO : decimal(usage.getFirst().get("cost_used"));
        long unpriced = usage.isEmpty() ? 0 : number(usage.getFirst().get("unpriced_calls"));
        long legacyUnknown = usage.isEmpty() ? 0 : number(usage.getFirst().get("legacy_unknown_attempts"));
        long tokenRemaining = tokenBudget <= 0 ? -1L : Math.max(0L, tokenBudget - tokenUsed);
        BigDecimal costRemaining = costBudget.signum() <= 0 ? BigDecimal.valueOf(-1)
            : costBudget.subtract(costUsed).max(BigDecimal.ZERO);
        boolean exhausted = tokenBudget > 0 && tokenUsed >= tokenBudget
            || costBudget.signum() > 0 && (costUsed.compareTo(costBudget) >= 0 || unpriced > 0)
            || (tokenBudget > 0 || costBudget.signum() > 0) && legacyUnknown > 0;
        return new LlmModelBudgetDto(month, tokenBudget, tokenUsed, tokenRemaining, costBudget, costUsed,
            costRemaining, exhausted, unpriced, legacyUnknown);
    }

    Map<String, Object> configuredModel(long tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select llm_provider, model_name from review_policy_config where tenant_id = ?", tenantId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    void reconcileCanaries(long tenantId) {
        List<LlmModelReleaseDto> canaries = releaseRepository.findByState(tenantId, "CANARY");
        if (canaries.isEmpty()) return;
        LlmModelBudgetDto budget = monthlyBudget(tenantId);
        for (LlmModelReleaseDto canary : canaries) {
            List<String> blockers = unsafeRuntimeBlockers(canary, budget);
            if (blockers.isEmpty()) continue;
            String reason = truncate("自动回滚: " + String.join(", ", blockers), 512);
            int updated = releaseRepository.rollback(tenantId, canary.id(), reason);
            if (updated == 1) {
                audit(tenantId, "AUTO_ROLLBACK", canary, releaseRepository.findById(tenantId, canary.id()),
                    "system", reason);
            }
        }
    }

    ReviewPolicySettings route(long tenantId, ReviewPolicySettings settings, ReviewTask task) {
        if (monthlyBudget(tenantId).exhausted()) return disableLlm(settings);
        LlmModelReleaseDto assigned = null;
        if (task.getLlmReleaseKey() != null && !task.getLlmReleaseKey().isBlank()) {
            assigned = assignedRelease(task, releaseRepository.findByReleaseKey(tenantId, task.getLlmReleaseKey()));
        }
        List<LlmModelReleaseDto> releases = null;
        if (assigned == null) {
            releases = releaseRepository.findAll(tenantId);
            assigned = assignedRelease(task, releases);
        }
        if (assigned != null) return assigned.safetyRevoked() ? disableLlm(settings) : routedSettings(settings, assigned);
        if (task.getLlmReleaseKey() != null && !task.getLlmReleaseKey().isBlank()) return disableLlm(settings);

        reconcileCanaries(tenantId);
        releases = releaseRepository.findAll(tenantId);
        LlmModelReleaseDto canary = firstState(releases, "CANARY");
        if (canary != null && inCanaryTraffic(task, canary)) {
            return assignAndRoute(tenantId, task, settings, canary);
        }
        LlmModelReleaseDto active = firstState(releases, "ACTIVE");
        if (active != null) return assignAndRoute(tenantId, task, settings, active);
        return releases.stream().anyMatch(LlmModelReleaseDto::safetyRevoked) ? disableLlm(settings) : settings;
    }

    void audit(long tenantId, String action, LlmModelReleaseDto before, LlmModelReleaseDto after,
        String operator, String reason) {
        LlmModelReleaseDto release = after == null ? before : after;
        if (release == null) return;
        String details;
        try {
            details = objectMapper.writeValueAsString(Map.of(
                "before", releaseSnapshot(before),
                "after", releaseSnapshot(after)
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("模型发布审计详情序列化失败", ex);
        }
        String normalizedReason = truncate(reason == null ? "" : reason, 512);
        releaseRepository.insertAudit(tenantId, release.id(), release.releaseKey(), action,
            before == null ? null : before.state(), after == null ? "ROLLED_BACK" : after.state(),
            after == null ? 0 : after.trafficPercent(), normalizeOperator(operator), normalizedReason,
            details, LlmModelReleaseAuditService.sha256(action, release.id(), release.releaseKey(), details, normalizedReason));
    }

    LlmModelReleaseDto firstState(List<LlmModelReleaseDto> releases, String state) {
        if (releases == null || state == null) return null;
        return releases.stream().filter(release -> !release.safetyRevoked() && state.equalsIgnoreCase(release.state())).findFirst().orElse(null);
    }

    private LlmModelReleaseDto assignedRelease(ReviewTask task, LlmModelReleaseDto release) {
        String key = task.getLlmReleaseKey();
        return key == null || key.isBlank() || release == null || !key.equals(release.releaseKey()) ? null : release;
    }

    private LlmModelReleaseDto assignedRelease(ReviewTask task, List<LlmModelReleaseDto> releases) {
        String key = task.getLlmReleaseKey();
        if (key == null || key.isBlank() || releases == null) return null;
        return releases.stream().filter(release -> key.equals(release.releaseKey())).findFirst().orElse(null);
    }

    private ReviewPolicySettings assignAndRoute(long tenantId, ReviewTask task, ReviewPolicySettings settings,
        LlmModelReleaseDto release) {
        task.setLlmReleaseKey(release.releaseKey());
        task.setLlmProvider(release.provider());
        task.setLlmModel(release.modelName());
        if (task.getId() != null) {
            jdbcTemplate.update("""
                update review_task
                   set llm_release_key = ?, llm_provider = ?, llm_model = ?
                 where tenant_id = ? and id = ? and status = 'REVIEWING'
                """, release.releaseKey(), release.provider(), release.modelName(), tenantId, task.getId());
        }
        return routedSettings(settings, release);
    }

    private ReviewPolicySettings routedSettings(ReviewPolicySettings settings, LlmModelReleaseDto release) {
        if (settings.llmProvider() == null || !settings.llmProvider().equalsIgnoreCase(release.provider())) return settings;
        return new ReviewPolicySettings(settings.exists(), settings.llmEnabled(), settings.llmProvider(), release.modelName(),
            settings.baseUrl(), settings.apiKey(), settings.timeoutSeconds(), settings.temperature(), settings.maxTokens(),
            settings.fallbackToRules(), settings.workerConcurrency(), settings.chunkFileThreshold(), settings.chunkLineThreshold(),
            settings.chunkMaxFiles(), settings.chunkMaxLines(), settings.inputTokenPricePerMillion(),
            settings.outputTokenPricePerMillion(), settings.strategyRelease(), settings.cachedInputTokenPricePerMillion());
    }

    private ReviewPolicySettings disableLlm(ReviewPolicySettings settings) {
        return new ReviewPolicySettings(settings.exists(), false, settings.llmProvider(), settings.modelName(), settings.baseUrl(),
            settings.apiKey(), settings.timeoutSeconds(), settings.temperature(), settings.maxTokens(), true,
            settings.workerConcurrency(), settings.chunkFileThreshold(), settings.chunkLineThreshold(), settings.chunkMaxFiles(),
            settings.chunkMaxLines(), settings.inputTokenPricePerMillion(), settings.outputTokenPricePerMillion(),
            settings.strategyRelease(), settings.cachedInputTokenPricePerMillion());
    }

    private List<String> unsafeRuntimeBlockers(LlmModelReleaseDto release, LlmModelBudgetDto budget) {
        List<String> blockers = new ArrayList<>(ReleaseQualityPolicy.blockerCodes(ReleaseQualityPolicy.Evidence.from(release)));
        if (budget.exhausted()) blockers.add("MONTHLY_LLM_BUDGET_EXHAUSTED");
        return List.copyOf(blockers);
    }

    private boolean inCanaryTraffic(ReviewTask task, LlmModelReleaseDto canary) {
        long key = task.getId() == null ? Math.max(1, task.getPrNumber() == null ? 1 : task.getPrNumber()) : task.getId();
        long bucket = Math.floorMod(key * 1103515245L + 12345L, 100L);
        return bucket < canary.trafficPercent();
    }

    private Map<String, Object> releaseSnapshot(LlmModelReleaseDto release) {
        if (release == null) return Map.of();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", release.id());
        snapshot.put("releaseKey", release.releaseKey());
        snapshot.put("provider", release.provider());
        snapshot.put("modelName", release.modelName());
        snapshot.put("promptVersion", release.promptVersion());
        snapshot.put("contextVersion", release.contextVersion());
        snapshot.put("schemaVersion", release.schemaVersion());
        snapshot.put("datasetId", release.datasetId());
        snapshot.put("datasetVersion", release.datasetVersion());
        snapshot.put("datasetFingerprint", release.datasetFingerprint());
        snapshot.put("state", release.state());
        snapshot.put("safetyRevoked", release.safetyRevoked());
        snapshot.put("safetyRevokedAt", release.safetyRevokedAt() == null ? null : release.safetyRevokedAt().toString());
        snapshot.put("safetyRevokeReason", release.safetyRevokeReason());
        snapshot.put("trafficPercent", release.trafficPercent());
        snapshot.put("qualityGatePassed", release.qualityGatePassed());
        snapshot.put("precisionRate", release.precisionRate());
        snapshot.put("recallRate", release.recallRate());
        snapshot.put("anchorRate", release.anchorRate());
        snapshot.put("duplicateRate", release.duplicateRate());
        snapshot.put("parseFailureRate", release.parseFailureRate());
        snapshot.put("p95LatencyMs", release.p95LatencyMs());
        snapshot.put("averageCost", release.averageCost());
        snapshot.put("totalTokens", release.totalTokens());
        snapshot.put("evaluationReportId", release.evaluationReportId());
        return snapshot;
    }

    private long number(Object value) {
        if (value instanceof Number number) return Math.max(0L, number.longValue());
        if (value == null) return 0L;
        try { return Math.max(0L, Long.parseLong(value.toString())); }
        catch (NumberFormatException ex) { return 0L; }
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.max(BigDecimal.ZERO);
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue()).max(BigDecimal.ZERO);
        if (value == null) return BigDecimal.ZERO;
        try { return new BigDecimal(value.toString()).max(BigDecimal.ZERO); }
        catch (NumberFormatException ex) { return BigDecimal.ZERO; }
    }

    private String normalizeOperator(String value) {
        return truncate(value == null || value.isBlank() ? "system" : value.trim(), 128);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** Shared quality specification; admission and runtime retain their own additional constraints. */
    static final class ReleaseQualityPolicy {
        private static final BigDecimal MIN_PRECISION = new BigDecimal("0.90");
        private static final BigDecimal MIN_RECALL = new BigDecimal("0.80");
        private static final BigDecimal MIN_ANCHOR = new BigDecimal("0.95");
        private static final BigDecimal MAX_ERROR_RATE = new BigDecimal("0.05");
        private static final long MAX_P95_LATENCY_MS = 15_000L;

        private ReleaseQualityPolicy() { }

        static List<Blocker> evaluate(Evidence evidence) {
            List<Blocker> blockers = new ArrayList<>();
            if (!Boolean.TRUE.equals(evidence.qualityGatePassed())) blockers.add(Blocker.QUALITY_GATE_FAILED);
            if (below(evidence.precision(), MIN_PRECISION)) blockers.add(Blocker.PRECISION_BELOW_90);
            if (below(evidence.recall(), MIN_RECALL)) blockers.add(Blocker.RECALL_BELOW_80);
            if (below(evidence.anchor(), MIN_ANCHOR)) blockers.add(Blocker.ANCHOR_RATE_BELOW_95);
            if (above(evidence.duplicate(), MAX_ERROR_RATE)) blockers.add(Blocker.DUPLICATE_RATE_ABOVE_5);
            if (above(evidence.parseFailure(), MAX_ERROR_RATE)) blockers.add(Blocker.PARSE_FAILURE_RATE_ABOVE_5);
            if (evidence.p95LatencyMs() == null || evidence.p95LatencyMs() > MAX_P95_LATENCY_MS) {
                blockers.add(Blocker.P95_LATENCY_ABOVE_15000_MS);
            }
            return List.copyOf(blockers);
        }

        static List<String> blockerCodes(Evidence evidence) {
            return evaluate(evidence).stream().map(Enum::name).toList();
        }

        private static boolean below(BigDecimal actual, BigDecimal minimum) {
            return actual == null || actual.compareTo(minimum) < 0;
        }

        private static boolean above(BigDecimal actual, BigDecimal maximum) {
            return actual == null || actual.compareTo(maximum) > 0;
        }

        record Evidence(Boolean qualityGatePassed, BigDecimal precision, BigDecimal recall, BigDecimal anchor,
                        BigDecimal duplicate, BigDecimal parseFailure, Long p95LatencyMs) {
            static Evidence from(LlmModelReleaseDto release) {
                return new Evidence(release.qualityGatePassed(), release.precisionRate(), release.recallRate(),
                    release.anchorRate(), release.duplicateRate(), release.parseFailureRate(), release.p95LatencyMs());
            }
        }

        enum Blocker {
            QUALITY_GATE_FAILED, PRECISION_BELOW_90, RECALL_BELOW_80, ANCHOR_RATE_BELOW_95,
            DUPLICATE_RATE_ABOVE_5, PARSE_FAILURE_RATE_ABOVE_5, P95_LATENCY_ABOVE_15000_MS
        }
    }

}
