package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciliationRequest;
import com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciledUsage;
import com.repoguard.agent.external.ExternalCallException;
import io.micrometer.core.instrument.MeterRegistry;
import com.repoguard.agent.review.LlmUsageBudgetMath.Bound;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One durable record per real HTTP attempt; task retries cannot erase accounted usage. */
@Component
public final class LlmUsageBudgetLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final TenantProperties tenancy;
    private final MeterRegistry metrics;

    public LlmUsageBudgetLedger(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper json) {
        this(jdbc, transactionManager, json, Clock.systemUTC());
    }

    public LlmUsageBudgetLedger(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
        ObjectMapper json, TenantProperties tenancy) {
        this(jdbc, transactionManager, json, Clock.systemUTC(), tenancy);
    }

    @Autowired
    public LlmUsageBudgetLedger(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
        ObjectMapper json, TenantProperties tenancy, MeterRegistry metrics) {
        this(jdbc, transactionManager, json, Clock.systemUTC(), tenancy, metrics);
    }

    LlmUsageBudgetLedger(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper json, Clock clock) {
        this(jdbc, transactionManager, json, clock, new TenantProperties());
    }

    LlmUsageBudgetLedger(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper json,
        Clock clock, TenantProperties tenancy) {
        this(jdbc, transactionManager, json, clock, tenancy, null);
    }

    LlmUsageBudgetLedger(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper json,
        Clock clock, TenantProperties tenancy, MeterRegistry metrics) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tenancy = Objects.requireNonNull(tenancy, "tenancy");
        this.metrics = metrics;
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(3);
    }

    public <T> T execute(ReviewTask task, String callKey, String operation, ReviewPolicySettings settings,
        String system, String user, int outputLimit, Supplier<T> request, Function<T, LlmCallResult> usage) {
        return execute(task, callKey, operation, settings, system, user, outputLimit, null, request, usage);
    }

    public <T> T execute(ReviewTask task, String callKey, String operation, ReviewPolicySettings settings,
        String system, String user, int outputLimit, Map<String, Object> payload,
        Supplier<T> request, Function<T, LlmCallResult> usage) {
        Bound bound = payload == null ? LlmUsageBudgetMath.bound(settings, system, user, outputLimit)
            : LlmUsageBudgetMath.bound(settings, encode(payload).getBytes(StandardCharsets.UTF_8).length, outputLimit);
        Reservation reservation = reserve(task, callKey, operation, settings, system, user, outputLimit, bound);
        try {
            T response = request.get();
            settle(reservation, settings, usage.apply(response));
            return response;
        } catch (AdmissionDeniedException notSent) {
            settle(reservation, settings, new LlmCallResult("", 0, 0, 0), notSent.getCategory());
            throw notSent;
        } catch (RuntimeException | Error failure) {
            try {
                jdbc.update("update llm_usage_call set status='UNKNOWN' where tenant_id=? and call_key=? and status='RESERVED'",
                    reservation.tenantId(), reservation.callKey());
            } catch (RuntimeException accountingFailure) {
                failure.addSuppressed(accountingFailure);
            }
            throw failure;
        }
    }

    Reservation reserve(ReviewTask task, String callKey, String operation, ReviewPolicySettings settings,
        String system, String user, int outputLimit) {
        return reserve(task, callKey, operation, settings, system, user, outputLimit,
            LlmUsageBudgetMath.bound(settings, system, user, outputLimit));
    }

    private Reservation reserve(ReviewTask task, String callKey, String operation, ReviewPolicySettings settings,
        String system, String user, int outputLimit, Bound bound) {
        long tenant = tenantId();
        if (callKey == null || callKey.isBlank() || callKey.length() > 128 || outputLimit < 1
            || operation == null || operation.isBlank() || operation.length() > 64) {
            throw denied("llm_budget_request_invalid");
        }
        Date month = Date.valueOf(YearMonth.now(clock).atDay(1));
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
            List<Map<String, Object>> quotas = jdbc.queryForList(
                "select monthly_llm_token_budget,monthly_llm_cost_budget from tenant_quota_config where tenant_id=? for update", tenant);
            long tokenLimit = quotas.isEmpty() ? 0 : number(quotas.getFirst(), "monthly_llm_token_budget");
            BigDecimal costLimit = quotas.isEmpty() ? BigDecimal.ZERO : decimal(quotas.getFirst(), "monthly_llm_cost_budget");
            jdbc.update("insert into llm_usage_month(tenant_id,usage_month) values(?,?) on duplicate key update tenant_id=values(tenant_id)",
                tenant, month);
            Map<String, Object> balance = jdbc.queryForMap(
                "select accounted_tokens,accounted_cost,unpriced_calls,legacy_unknown_attempts from llm_usage_month where tenant_id=? and usage_month=? for update",
                tenant, month);
            long nextTokens = LlmUsageBudgetMath.add(number(balance, "accounted_tokens"), bound.tokens());
            BigDecimal nextCost = decimal(balance, "accounted_cost").add(bound.cost() == null ? BigDecimal.ZERO : bound.cost());
            if ((tokenLimit > 0 || costLimit.signum() > 0) && number(balance, "legacy_unknown_attempts") > 0) {
                throw denied("llm_budget_legacy_usage_unknown");
            }
            if (costLimit.signum() > 0 && (bound.cost() == null || number(balance, "unpriced_calls") > 0)) {
                throw denied("llm_budget_pricing_unknown");
            }
            if (tokenLimit > 0 && nextTokens > tokenLimit || costLimit.signum() > 0 && nextCost.compareTo(costLimit) > 0) {
                throw denied("llm_monthly_budget_exhausted");
            }
            String pricing = encode(new LlmReviewCostEstimator().snapshot(settings, null, true));
            String fingerprint = fingerprint(operation, system, user);
            int inserted = jdbc.update("""
                insert into llm_usage_call(tenant_id,call_key,usage_month,task_id,attempt_id,operation,
                    provider,model_name,request_fingerprint,status,accounted_tokens,accounted_cost,pricing_json,started_at)
                values(?,?,?,?,?,?,?,?,?,'RESERVED',?,?,?,?)
                """, tenant, callKey, month, task == null ? null : task.getId(),
                task == null ? null : task.getCurrentAttemptId(), operation, settings.llmProvider(),
                settings.modelName(), fingerprint, bound.tokens(), bound.cost(), pricing, now());
            if (inserted != 1) {
                throw new IllegalStateException("LLM reservation was not durably inserted");
            }
            jdbc.update("""
                update llm_usage_month set accounted_tokens=?,accounted_cost=?,unpriced_calls=unpriced_calls+?,updated_at=?
                where tenant_id=? and usage_month=?
                """, nextTokens, nextCost, bound.cost() == null ? 1 : 0, now(), tenant, month);
            return new Reservation(tenant, callKey, month, bound);
            }));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw denied("llm_budget_duplicate_call");
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw denied("llm_budget_store_unavailable");
        }
    }

    void settle(Reservation reservation, ReviewPolicySettings settings, LlmCallResult usage) {
        settle(reservation, settings, usage, null);
    }

    private void settle(Reservation reservation, ReviewPolicySettings settings, LlmCallResult usage, String notSent) {
        Bound reconciled = LlmUsageBudgetMath.reconcile(reservation.bound(), settings, usage);
        Map<String, Object> usageEvidence = new java.util.LinkedHashMap<>();
        usageEvidence.put("requestSent", notSent == null);
        if (notSent != null) usageEvidence.put("notSentCategory", notSent);
        usageEvidence.put("promptTokens", usage == null ? null : usage.promptTokens());
        usageEvidence.put("completionTokens", usage == null ? null : usage.completionTokens());
        usageEvidence.put("totalTokens", usage == null ? null : usage.totalTokens());
        usageEvidence.put("costSnapshot", new LlmReviewCostEstimator().snapshot(settings, usage, !LlmUsageBudgetMath.complete(usage)));
        String evidence = encode(usageEvidence);
        transactions.executeWithoutResult(status -> {
            // All accounting writers acquire the month before the call, avoiding reverse lock order.
            jdbc.queryForMap("select accounted_tokens from llm_usage_month where tenant_id=? and usage_month=? for update",
                reservation.tenantId(), reservation.month());
            Map<String, Object> current = jdbc.queryForMap(
                "select status,accounted_tokens,accounted_cost from llm_usage_call where tenant_id=? and call_key=? for update",
                reservation.tenantId(), reservation.callKey());
            if ("SETTLED".equals(current.get("status")) || "LEGACY".equals(current.get("status"))) {
                return;
            }
            long previousTokens = number(current, "accounted_tokens");
            BigDecimal previousCost = current.get("accounted_cost") == null ? null : decimal(current, "accounted_cost");
            BigDecimal currentCost = reconciled.cost();
            int unpricedDelta = (currentCost == null ? 1 : 0) - (previousCost == null ? 1 : 0);
            jdbc.update("""
                update llm_usage_call set status=?,accounted_tokens=?,accounted_cost=?,usage_json=?,settled_at=?
                where tenant_id=? and call_key=?
                """, LlmUsageBudgetMath.complete(usage) ? "SETTLED" : "UNKNOWN", reconciled.tokens(),
                currentCost, evidence, now(), reservation.tenantId(), reservation.callKey());
            jdbc.update("""
                update llm_usage_month set accounted_tokens=accounted_tokens+?,accounted_cost=accounted_cost+?,
                    unpriced_calls=unpriced_calls+?,updated_at=? where tenant_id=? and usage_month=?
                """, reconciled.tokens() - previousTokens,
                (currentCost == null ? BigDecimal.ZERO : currentCost).subtract(previousCost == null ? BigDecimal.ZERO : previousCost),
                unpricedDelta, now(), reservation.tenantId(), reservation.month());
        });
    }

    void requireReleaseAllowed(ReviewTask task) {
        if (task == null || task.getLlmReleaseKey() == null || task.getLlmReleaseKey().isBlank()) return;
        long tenant = tenantId();
        transactions.executeWithoutResult(status -> {
            List<Map<String, Object>> releases = jdbc.queryForList("""
                select safety_revoked_at from llm_model_release where tenant_id=? and release_key=? for share
                """, tenant, task.getLlmReleaseKey());
            if (releases.isEmpty() || releases.getFirst().get("safety_revoked_at") != null) {
                throw new AdmissionDeniedException("llm_release_safety_revoked");
            }
        });
    }

    public ReconciledUsage reconcile(ReconciliationRequest request, String operator) {
        validateReconciliation(request, operator);
        long tenant = tenantId();
        String requestHash = fingerprint("reconcile", encode(request), "");
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                ReconciledUsage replay = reconciliation(tenant, request, requestHash);
                if (replay != null) return replay;
                List<Map<String, Object>> calls = jdbc.queryForList(
                    "select usage_month from llm_usage_call where tenant_id=? and call_key=?", tenant, request.callKey());
                if (calls.isEmpty()) throw conflict("Accounting record is unavailable");
                Date month = Date.valueOf(calls.getFirst().get("usage_month").toString());
                jdbc.queryForMap("select accounted_tokens from llm_usage_month where tenant_id=? and usage_month=? for update", tenant, month);
                ReconciledUsage lockedReplay = reconciliation(tenant, request, requestHash, true);
                if (lockedReplay != null) return lockedReplay;
                // Lock order is shared with admission and provider settlement: month, then call.
                Map<String, Object> previous = jdbc.queryForMap("""
                    select status,accounted_tokens,accounted_cost,pricing_json,usage_json,started_at,
                        json_unquote(json_extract(usage_json,'$.legacyUnknown')) as legacy_unknown,
                        json_unquote(json_extract(usage_json,'$.legacyUnpriced')) as legacy_unpriced
                    from llm_usage_call where tenant_id=? and call_key=? for update
                    """, tenant, request.callKey());
                if ("SETTLED".equals(previous.get("status"))) throw conflict("Confirmed provider usage is immutable");
                LocalDateTime started = timestamp(previous, "started_at");
                if ("RESERVED".equals(previous.get("status")) && started.isAfter(now().minusHours(1))) {
                    throw conflict("An active reservation cannot be reconciled; wait for final provider evidence");
                }
                long previousTokens = number(previous, "accounted_tokens");
                boolean legacy = "LEGACY".equals(previous.get("status"));
                boolean unpriced = legacy ? "1".equals(previous.get("legacy_unpriced")) || "true".equals(previous.get("legacy_unpriced"))
                    : previous.get("accounted_cost") == null;
                boolean legacyUnknown = legacy && ("1".equals(previous.get("legacy_unknown")) || "true".equals(previous.get("legacy_unknown")));
                BigDecimal previousCost = previous.get("accounted_cost") == null ? BigDecimal.ZERO : decimal(previous, "accounted_cost");
                LocalDateTime completed = now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                Map<String, Object> previousEvidence = new java.util.LinkedHashMap<>(previous);
                previousEvidence.put("started_at", started.toString());
                jdbc.update("""
                    update llm_usage_month set accounted_tokens=accounted_tokens+?,accounted_cost=accounted_cost+?,
                        unpriced_calls=unpriced_calls-?,legacy_unknown_attempts=legacy_unknown_attempts-?,updated_at=?
                    where tenant_id=? and usage_month=?
                    """, request.accountedTokens() - previousTokens, request.accountedCost().subtract(previousCost),
                    unpriced ? 1 : 0, legacyUnknown ? 1 : 0, completed, tenant, month);
                jdbc.update("""
                    update llm_usage_call set status='SETTLED',accounted_tokens=?,accounted_cost=?,
                        usage_json=?,settled_at=? where tenant_id=? and call_key=?
                    """, request.accountedTokens(), request.accountedCost(), encode(Map.of(
                        "source", "operator_final_evidence", "evidenceReference", request.evidenceReference())),
                    completed, tenant, request.callKey());
                jdbc.update("""
                    insert into llm_usage_reconciliation(tenant_id,idempotency_key,call_key,request_fingerprint,
                        operator_name,evidence_reference,reason,previous_evidence_json,accounted_tokens,accounted_cost,created_at)
                    values(?,?,?,?,?,?,?,?,?,?,?)
                    """, tenant, request.idempotencyKey(), request.callKey(), requestHash, operator,
                    request.evidenceReference(), request.reason(), encode(previousEvidence), request.accountedTokens(), request.accountedCost(), completed);
                return reconciliation(tenant, request, requestHash);
            }));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // A competing idempotent operation rolls its balance changes back before this read.
            ReconciledUsage replay = transactions.execute(status -> reconciliation(tenant, request, requestHash));
            if (replay == null) throw conflict("Concurrent reconciliation must be retried with the same key");
            return replay;
        }
    }

    private ReconciledUsage reconciliation(long tenant, ReconciliationRequest request, String expectedHash) {
        return reconciliation(tenant, request, expectedHash, false);
    }

    private ReconciledUsage reconciliation(long tenant, ReconciliationRequest request, String expectedHash, boolean locked) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select * from llm_usage_reconciliation where tenant_id=? and idempotency_key=?" + (locked ? " for update" : ""),
            tenant, request.idempotencyKey());
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.getFirst();
        if (!expectedHash.equals(row.get("request_fingerprint"))) throw conflict("Idempotency key already has different evidence");
        return new ReconciledUsage(request.idempotencyKey(), row.get("call_key").toString(), number(row, "accounted_tokens"),
            decimal(row, "accounted_cost"), row.get("operator_name").toString(), row.get("evidence_reference").toString(),
            row.get("reason").toString(), timestamp(row, "created_at").atOffset(ZoneOffset.UTC));
    }

    private void validateReconciliation(ReconciliationRequest request, String operator) {
        if (request == null || !request.finalEvidence() || request.accountedTokens() < 0
            || request.accountedTokens() > 9_007_199_254_740_991L || request.accountedCost() == null
            || request.accountedCost().signum() < 0 || request.accountedCost().scale() > 8
            || request.accountedCost().precision() - request.accountedCost().scale() > 14
            || request.callKey() == null || !request.callKey().matches("[A-Za-z0-9:._-]{1,128}")
            || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9._-]{1,64}")
            || !boundedText(request.reason(), 512) || !boundedText(request.evidenceReference(), 512)
            || !boundedText(operator, 128)) throw new BusinessException(ErrorCode.BAD_REQUEST, "Final accounting evidence is invalid");
    }

    private static boolean boundedText(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }

    private static LocalDateTime timestamp(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof java.sql.Timestamp time) return time.toLocalDateTime();
        throw new IllegalStateException("Missing accounting timestamp: " + key);
    }

    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message); }

    private long tenantId() {
        if (tenancy.isEnabled() && TenantContext.currentTenantId() == null) {
            throw denied("llm_budget_tenant_context_missing");
        }
        return TenantContext.currentTenantIdOrDefault();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize LLM accounting evidence", ex);
        }
    }

    private static String fingerprint(String operation, String system, String user) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String value : new String[] {operation, system, user}) {
                byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("Missing numeric accounting field: " + key);
        }
        long result = new BigDecimal(value.toString()).longValueExact();
        if (result < 0) {
            throw new IllegalStateException("Negative accounting field: " + key);
        }
        return result;
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("Missing cost accounting field: " + key);
        }
        BigDecimal result = new BigDecimal(value.toString());
        if (result.signum() < 0) {
            throw new IllegalStateException("Negative cost accounting field: " + key);
        }
        return result;
    }

    private AdmissionDeniedException denied(String category) {
        // Categories are private, fixed call-site constants; request data never becomes a label.
        if (metrics != null) metrics.counter("repoguard.llm.budget.denied", "reason", category).increment();
        return new AdmissionDeniedException(category);
    }

    public static final class AdmissionDeniedException extends ExternalCallException {
        private static final long serialVersionUID = 1L;
        AdmissionDeniedException(String category) {
            super("LLM", category, false, null, "request not sent; LLM admission denied", null);
        }
    }

    record Reservation(long tenantId, String callKey, Date month, Bound bound) {
    }
}
