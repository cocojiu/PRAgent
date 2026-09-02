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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private static final BigDecimal MIN_PRECISION = new BigDecimal("0.90");
    private static final BigDecimal MIN_RECALL = new BigDecimal("0.80");
    private static final BigDecimal MIN_ANCHOR_RATE = new BigDecimal("0.95");
    private static final BigDecimal MAX_DUPLICATE_RATE = new BigDecimal("0.05");
    private static final BigDecimal MAX_PARSE_FAILURE_RATE = new BigDecimal("0.05");
    private static final long MAX_P95_LATENCY_MS = 15_000L;

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
        YearMonth month = YearMonth.now();
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        List<Map<String, Object>> limits = jdbcTemplate.queryForList(
            "select monthly_llm_token_budget, monthly_llm_cost_budget from tenant_quota_config where tenant_id = ?",
            tenantId);
        long tokenBudget = limits.isEmpty() ? 0L : number(limits.getFirst().get("monthly_llm_token_budget"));
        BigDecimal costBudget = limits.isEmpty() ? BigDecimal.ZERO
            : decimal(limits.getFirst().get("monthly_llm_cost_budget"));
        List<Map<String, Object>> usage = jdbcTemplate.queryForList(
            "select coalesce(sum(llm_total_tokens), 0) as token_used, coalesce(sum(llm_estimated_cost), 0) as cost_used "
                + "from review_task where tenant_id = ? and created_at >= ? and created_at < ? "
                + "and llm_status_norm <> '' and llm_status_norm <> 'pending'",
            tenantId, start, end);
        long tokenUsed = usage.isEmpty() ? 0L : number(usage.getFirst().get("token_used"));
        BigDecimal costUsed = usage.isEmpty() ? BigDecimal.ZERO : decimal(usage.getFirst().get("cost_used"));
        long tokenRemaining = tokenBudget <= 0 ? -1L : Math.max(0L, tokenBudget - tokenUsed);
        BigDecimal costRemaining = costBudget.signum() <= 0 ? BigDecimal.valueOf(-1)
            : costBudget.subtract(costUsed).max(BigDecimal.ZERO);
        boolean exhausted = tokenBudget > 0 && tokenUsed >= tokenBudget
            || costBudget.signum() > 0 && costUsed.compareTo(costBudget) >= 0;
        return new LlmModelBudgetDto(month, tokenBudget, tokenUsed, tokenRemaining, costBudget, costUsed,
            costRemaining, exhausted);
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
        if (assigned != null) return routedSettings(settings, assigned);

        reconcileCanaries(tenantId);
        releases = releaseRepository.findAll(tenantId);
        LlmModelReleaseDto canary = firstState(releases, "CANARY");
        if (canary != null && inCanaryTraffic(task, canary)) {
            return assignAndRoute(tenantId, task, settings, canary);
        }
        LlmModelReleaseDto active = firstState(releases, "ACTIVE");
        return active == null ? settings : assignAndRoute(tenantId, task, settings, active);
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
            details, sha256(action + "|" + release.id() + "|" + release.releaseKey() + "|" + details + "|" + reason));
    }

    LlmModelReleaseDto firstState(List<LlmModelReleaseDto> releases, String state) {
        if (releases == null || state == null) return null;
        return releases.stream().filter(release -> state.equalsIgnoreCase(release.state())).findFirst().orElse(null);
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
            settings.outputTokenPricePerMillion(), settings.strategyRelease());
    }

    private ReviewPolicySettings disableLlm(ReviewPolicySettings settings) {
        return new ReviewPolicySettings(settings.exists(), false, settings.llmProvider(), settings.modelName(), settings.baseUrl(),
            settings.apiKey(), settings.timeoutSeconds(), settings.temperature(), settings.maxTokens(), true,
            settings.workerConcurrency(), settings.chunkFileThreshold(), settings.chunkLineThreshold(), settings.chunkMaxFiles(),
            settings.chunkMaxLines(), settings.inputTokenPricePerMillion(), settings.outputTokenPricePerMillion(),
            settings.strategyRelease());
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

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for release audits", ex);
        }
    }
}
