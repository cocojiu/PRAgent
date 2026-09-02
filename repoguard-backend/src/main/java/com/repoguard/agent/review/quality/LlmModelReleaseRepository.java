package com.repoguard.agent.review.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Tenant-scoped persistence for model releases and aggregate-only evaluation reports. */
@Repository
public class LlmModelReleaseRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LlmModelReleaseRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }
    /** Inserts an immutable aggregate report; deterministic retries return the original row. */
    public StoredEvaluationReport insertEvaluationReport(long tenantId, String reportKey,
        LlmEvaluationReport report, String operator) {
        StoredEvaluationReport existing = findEvaluationReportByKeyOrNull(tenantId, reportKey);
        if (existing != null) return existing;
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                    insert into llm_evaluation_report (tenant_id, report_key, status, dataset_id, dataset_version,
                    dataset_kind, source_repository_count, sample_count, fixed_regression_samples,
                    rolling_observation_samples, authorized, anonymized, human_reviewed, manifest_fingerprint,
                    observed_sample_fingerprint, provider, model, prompt_version, context_version, schema_version,
                    chunk_policy_version, temperature, rule_version, code_revision, expected_findings,
                    predicted_findings, true_positives, false_positives, false_negatives, precision_rate, recall_rate,
                    precision_wilson_lower_bound, anchor_rate, duplicate_rate, parse_failure_rate, severity_confusion_json,
                    total_latency_ms, total_tokens, total_cost, blockers_json, eligible, metrics_json, created_by, created_at)
                    values (
                        ?, ?, 'COMPLETED',
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp(6)
                    )
                    """, Statement.RETURN_GENERATED_KEYS);
                bind(statement, evaluationValues(tenantId, reportKey, report, operator));
                return statement;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) throw new IllegalStateException("评估报告未返回主键");
            return findEvaluationReport(tenantId, key.longValue());
        } catch (DuplicateKeyException ex) {
            StoredEvaluationReport retry = findEvaluationReportByKeyOrNull(tenantId, reportKey);
            if (retry != null) return retry;
            throw ex;
        }
    }
    private Object[] evaluationValues(long tenantId, String reportKey, LlmEvaluationReport report, String operator) {
        LlmEvaluationDatasetMetadata d = report.dataset();
        LlmEvaluationVersion v = report.version();
        return new Object[] {tenantId, reportKey, d.datasetId(), d.datasetVersion(), d.kind().name(), d.sourceRepositoryCount(),
            d.sampleCount(), d.fixedRegressionSamples(), d.rollingObservationSamples(), d.authorized(), d.anonymized(),
            d.humanReviewed(), d.sampleFingerprint(), report.sampleFingerprint(), v.provider(), v.model(), v.promptVersion(),
            v.contextVersion(), v.schemaVersion(), v.chunkPolicyVersion(), v.temperature(), v.ruleVersion(), v.codeRevision(),
            report.expectedFindings(), report.predictedFindings(), report.truePositives(), report.falsePositives(),
            report.falseNegatives(), report.precision(), report.recall(), report.precisionWilsonLowerBound(), report.anchorRate(),
            report.duplicateRate(), report.parseFailureRate(), json(report.severityConfusion()), report.totalLatencyMs(),
            report.totalTokens(), report.totalCost(), json(report.blockers()), report.eligible(), json(report.metrics()), operator};
    }
    private void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }
    public StoredEvaluationReport findEvaluationReport(long tenantId, long reportId) {
        List<StoredEvaluationReport> reports = jdbcTemplate.query("select * from llm_evaluation_report where tenant_id = ? and id = ?",
            this::mapEvaluationReport, tenantId, reportId);
        if (reports.size() != 1) throw new BusinessException(ErrorCode.BAD_REQUEST, "评估报告不存在");
        return reports.getFirst();
    }
    public List<StoredEvaluationReport> findEvaluationReports(long tenantId, int limit) {
        return jdbcTemplate.query("select * from llm_evaluation_report where tenant_id = ? order by created_at desc, id desc limit ?",
            this::mapEvaluationReport, tenantId, Math.max(1, Math.min(100, limit)));
    }
    private StoredEvaluationReport findEvaluationReportByKeyOrNull(long tenantId, String reportKey) {
        List<StoredEvaluationReport> reports = jdbcTemplate.query("select * from llm_evaluation_report where tenant_id = ? and report_key = ?",
            this::mapEvaluationReport, tenantId, reportKey);
        return reports.isEmpty() ? null : reports.getFirst();
    }
    public LlmModelReleaseDto save(long tenantId, LlmModelReleaseService.NormalizedRelease release, String state,
        int trafficPercent, String operator, String rollbackReason) {
        List<Long> ids = jdbcTemplate.query("select id from llm_model_release where tenant_id = ? and release_key = ?",
            (rs, rowNum) -> rs.getLong(1), tenantId, release.releaseKey());
        String blockers = release.blockers().stream().collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                    insert into llm_model_release (tenant_id, release_key, provider, model_name, prompt_version, context_version,
                    schema_version, dataset_id, dataset_version, dataset_fingerprint, evaluation_report_id, state, traffic_percent,
                    quality_gate_passed, precision_rate, recall_rate, anchor_rate, duplicate_rate, parse_failure_rate, p95_latency_ms,
                    average_cost, total_tokens, blockers, rollback_reason, created_by, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp(6), current_timestamp(6))
                    """, Statement.RETURN_GENERATED_KEYS);
                bind(statement, new Object[] {tenantId, release.releaseKey(), release.provider(), release.modelName(), release.promptVersion(),
                    release.contextVersion(), release.schemaVersion(), release.datasetId(), release.datasetVersion(), release.datasetFingerprint(),
                    release.evaluationReportId(), state, trafficPercent, release.qualityGatePassed(), release.precisionRate(), release.recallRate(),
                    release.anchorRate(), release.duplicateRate(), release.parseFailureRate(), release.p95LatencyMs(), release.averageCost(),
                    release.totalTokens(), blockers, blankToNull(rollbackReason), operator});
                return statement;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) throw new IllegalStateException("模型发布记录未返回主键");
            return findById(tenantId, key.longValue());
        }
        jdbcTemplate.update("""
            update llm_model_release set provider = ?, model_name = ?, prompt_version = ?, context_version = ?, schema_version = ?,
            dataset_id = ?, dataset_version = ?, dataset_fingerprint = ?, evaluation_report_id = ?, state = ?, traffic_percent = ?,
            quality_gate_passed = ?, precision_rate = ?, recall_rate = ?, anchor_rate = ?, duplicate_rate = ?, parse_failure_rate = ?,
            p95_latency_ms = ?, average_cost = ?, total_tokens = ?, blockers = ?, rollback_reason = ?, created_by = ?, updated_at = current_timestamp(6)
            where tenant_id = ? and release_key = ?
            """, release.provider(), release.modelName(), release.promptVersion(), release.contextVersion(), release.schemaVersion(),
            release.datasetId(), release.datasetVersion(), release.datasetFingerprint(), release.evaluationReportId(), state, trafficPercent,
            release.qualityGatePassed(), release.precisionRate(), release.recallRate(), release.anchorRate(), release.duplicateRate(),
            release.parseFailureRate(), release.p95LatencyMs(), release.averageCost(), release.totalTokens(), blockers, blankToNull(rollbackReason),
            operator, tenantId, release.releaseKey());
        return findById(tenantId, ids.getFirst());
    }
    public LlmModelReleaseDto findById(long tenantId, long releaseId) {
        List<LlmModelReleaseDto> releases = jdbcTemplate.query("select * from llm_model_release where tenant_id = ? and id = ?",
            this::mapRelease, tenantId, releaseId);
        if (releases.size() != 1) throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布记录不存在");
        return releases.getFirst();
    }
    public List<LlmModelReleaseDto> findAll(long tenantId) {
        return jdbcTemplate.query("select * from llm_model_release where tenant_id = ? order by updated_at desc, id desc limit 50",
            this::mapRelease, tenantId);
    }
    public List<LlmModelReleaseDto> findByState(long tenantId, String state) {
        return jdbcTemplate.query("select * from llm_model_release where tenant_id = ? and state = ? order by updated_at desc, id desc limit 10",
            this::mapRelease, tenantId, state);
    }

    /** Locks the tenant row so a release transition is serialized across application instances. */
    public void lockTenant(long tenantId) {
        Long lockedTenant = jdbcTemplate.queryForObject(
            "select id from tenant where id = ? for update", Long.class, tenantId
        );
        if (lockedTenant == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "租户不存在，不能变更模型发布");
        }
    }

    public LlmModelReleaseDto findByReleaseKey(long tenantId, String releaseKey) {
        List<LlmModelReleaseDto> releases = jdbcTemplate.query(
            "select * from llm_model_release where tenant_id = ? and release_key = ?",
            this::mapRelease, tenantId, releaseKey
        );
        return releases.isEmpty() ? null : releases.getFirst();
    }

    public int markActiveReplaced(long tenantId) {
        return jdbcTemplate.update("update llm_model_release set state = 'ROLLED_BACK', traffic_percent = 0, rollback_reason = 'replaced by a newer active release' where tenant_id = ? and state = 'ACTIVE'", tenantId);
    }
    public int rollback(long tenantId, long releaseId, String reason) {
        return jdbcTemplate.update("update llm_model_release set state = 'ROLLED_BACK', traffic_percent = 0, rollback_reason = ?, updated_at = current_timestamp(6) where tenant_id = ? and id = ? and state <> 'ROLLED_BACK'", reason, tenantId, releaseId);
    }

    /** Append-only transition record; there is intentionally no update/delete path for audits. */
    public void insertAudit(long tenantId, long releaseId, String releaseKey, String action,
        String fromState, String toState, int trafficPercent, String operator, String reason,
        String detailsJson, String eventHash) {
        jdbcTemplate.update("""
            insert into llm_model_release_audit
                (tenant_id, release_id, release_key, action, from_state, to_state, traffic_percent,
                 operator, reason, details_json, event_hash, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp(6))
            """, tenantId, releaseId, releaseKey, action, fromState, toState, trafficPercent,
            operator, reason, detailsJson, eventHash);
    }

    /** Returns a tenant-scoped count for the bounded release-audit query. */
    public long countAudits(long tenantId, AuditFilter filter) {
        AuditQuery query = auditQuery("select count(*) from llm_model_release_audit", tenantId, filter, null, null);
        Long count = jdbcTemplate.queryForObject(query.sql(), Long.class, query.args());
        return count == null ? 0L : Math.max(0L, count);
    }

    /** Lists immutable audit rows in newest-first order with a caller-supplied bounded page. */
    public List<ReleaseAudit> findAudits(long tenantId, AuditFilter filter, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(1_000, limit));
        AuditQuery query = auditQuery("""
            select id, release_id, release_key, action, from_state, to_state, traffic_percent,
                   operator, reason, details_json, event_hash, created_at
              from llm_model_release_audit
            """, tenantId, filter, safeOffset, safeLimit);
        return jdbcTemplate.query(query.sql(), this::mapAudit, query.args());
    }

    /** Looks up one audit row under the current tenant; cross-tenant ids intentionally return null. */
    public ReleaseAudit findAuditById(long tenantId, long auditId) {
        AuditQuery query = auditQuery("""
            select id, release_id, release_key, action, from_state, to_state, traffic_percent,
                   operator, reason, details_json, event_hash, created_at
              from llm_model_release_audit
            """, tenantId, new AuditFilter(auditId, null, null, null, null, null), null, null);
        List<ReleaseAudit> rows = jdbcTemplate.query(query.sql(), this::mapAudit, query.args());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private AuditQuery auditQuery(String select, long tenantId, AuditFilter filter, Integer offset, Integer limit) {
        AuditFilter safeFilter = filter == null ? AuditFilter.empty() : filter;
        StringBuilder sql = new StringBuilder(select).append(" where tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (safeFilter.auditId() != null) {
            sql.append(" and id = ?");
            args.add(safeFilter.auditId());
        }
        if (safeFilter.releaseId() != null) {
            sql.append(" and release_id = ?");
            args.add(safeFilter.releaseId());
        }
        if (safeFilter.releaseKey() != null && !safeFilter.releaseKey().isBlank()) {
            sql.append(" and release_key = ?");
            args.add(safeFilter.releaseKey());
        }
        if (safeFilter.operator() != null && !safeFilter.operator().isBlank()) {
            sql.append(" and operator = ?");
            args.add(safeFilter.operator());
        }
        if (safeFilter.action() != null && !safeFilter.action().isBlank()) {
            sql.append(" and action = ?");
            args.add(safeFilter.action());
        }
        if (safeFilter.from() != null) {
            sql.append(" and created_at >= ?");
            args.add(safeFilter.from());
        }
        if (safeFilter.to() != null) {
            sql.append(" and created_at < ?");
            args.add(safeFilter.to());
        }
        if (offset != null && limit != null) {
            sql.append(" order by created_at desc, id desc limit ? offset ?");
            args.add(limit);
            args.add(offset);
        }
        return new AuditQuery(sql.toString(), args.toArray());
    }

    private ReleaseAudit mapAudit(java.sql.ResultSet rs, int rowNum) throws SQLException {
        return new ReleaseAudit(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("release_key"), rs.getString("action"),
            rs.getString("from_state"), rs.getString("to_state"), rs.getInt("traffic_percent"),
            rs.getString("operator"), rs.getString("reason"), rs.getString("details_json"),
            rs.getString("event_hash"), time(rs, "created_at")
        );
    }

    private StoredEvaluationReport mapEvaluationReport(java.sql.ResultSet rs, int rowNum) throws SQLException {
        LlmEvaluationVersion v = new LlmEvaluationVersion(rs.getString("provider"), rs.getString("model"), rs.getString("prompt_version"),
            rs.getString("context_version"), rs.getString("schema_version"), rs.getString("chunk_policy_version"), rs.getBigDecimal("temperature"),
            rs.getString("rule_version"), rs.getString("code_revision"));
        LlmEvaluationDatasetMetadata d = new LlmEvaluationDatasetMetadata(rs.getString("dataset_id"), rs.getString("dataset_version"),
            LlmEvaluationDatasetMetadata.DatasetKind.valueOf(rs.getString("dataset_kind")), rs.getInt("source_repository_count"),
            rs.getInt("sample_count"), rs.getInt("fixed_regression_samples"), rs.getInt("rolling_observation_samples"),
            rs.getBoolean("authorized"), rs.getBoolean("anonymized"), rs.getBoolean("human_reviewed"), rs.getString("manifest_fingerprint"));
        LlmEvaluationReport report = new LlmEvaluationReport(v, rs.getString("observed_sample_fingerprint"), rs.getInt("sample_count"),
            rs.getInt("expected_findings"), rs.getInt("predicted_findings"), rs.getInt("true_positives"), rs.getInt("false_positives"),
            rs.getInt("false_negatives"), rs.getBigDecimal("precision_rate"), rs.getBigDecimal("recall_rate"), rs.getBigDecimal("precision_wilson_lower_bound"),
            rs.getBigDecimal("anchor_rate"), rs.getBigDecimal("duplicate_rate"), rs.getBigDecimal("parse_failure_rate"), readSeverityConfusion(rs.getString("severity_confusion_json")),
            rs.getLong("total_latency_ms"), rs.getLong("total_tokens"), rs.getBigDecimal("total_cost"), readStringList(rs.getString("blockers_json")),
            rs.getBoolean("eligible"), d, readMetrics(rs.getString("metrics_json")));
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        return new StoredEvaluationReport(rs.getLong("id"), rs.getString("report_key"), rs.getString("status"), rs.getString("created_by"),
            createdAt == null ? null : createdAt.toLocalDateTime(), report);
    }

    private Map<String, Map<String, Long>> readSeverityConfusion(String value) {
        return read(value, new TypeReference<>() { }, "评估报告严重级别矩阵已损坏", Map.of());
    }

    private List<String> readStringList(String value) {
        return read(value, new TypeReference<>() { }, "评估报告门禁阻断项已损坏", List.of());
    }

    private LlmEvaluationMetrics readMetrics(String value) {
        return read(value, new TypeReference<LlmEvaluationMetrics>() { }, "评估报告运营指标已损坏", LlmEvaluationMetrics.empty());
    }

    private <T> T read(String value, TypeReference<T> type, String message, T empty) {
        if (value == null || value.isBlank()) return empty;
        try { return objectMapper.readValue(value, type); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(message, ex); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("评估报告聚合指标序列化失败", ex); }
    }

    private LlmModelReleaseDto mapRelease(java.sql.ResultSet rs, int rowNum) throws SQLException {
        return new LlmModelReleaseDto(rs.getLong("id"), rs.getString("release_key"), rs.getString("provider"), rs.getString("model_name"),
            rs.getString("prompt_version"), rs.getString("context_version"), rs.getString("schema_version"), rs.getString("dataset_id"),
            rs.getString("dataset_version"), rs.getString("dataset_fingerprint"), rs.getString("state"), rs.getInt("traffic_percent"),
            rs.getBoolean("quality_gate_passed"), rs.getBigDecimal("precision_rate"), rs.getBigDecimal("recall_rate"), rs.getBigDecimal("anchor_rate"),
            rs.getBigDecimal("duplicate_rate"), rs.getBigDecimal("parse_failure_rate"), rs.getLong("p95_latency_ms"), rs.getBigDecimal("average_cost"),
            rs.getLong("total_tokens"), parseBlockers(rs.getString("blockers")), rs.getString("rollback_reason"), rs.getString("created_by"),
            time(rs, "created_at"), time(rs, "updated_at"), rs.getObject("evaluation_report_id") == null ? null : rs.getLong("evaluation_report_id"));
    }

    private LocalDateTime time(java.sql.ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private List<String> parseBlockers(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    public record StoredEvaluationReport(Long id, String reportKey, String status, String createdBy, LocalDateTime createdAt,
        LlmEvaluationReport report) { }

    public record AuditFilter(Long auditId, Long releaseId, String releaseKey, String operator, String action,
        LocalDateTime from, LocalDateTime to) {
        public AuditFilter(Long releaseId, String releaseKey, String operator, String action,
            LocalDateTime from, LocalDateTime to) {
            this(null, releaseId, releaseKey, operator, action, from, to);
        }

        public static AuditFilter empty() {
            return new AuditFilter(null, null, null, null, null, null, null);
        }
    }

    public record ReleaseAudit(Long id, Long releaseId, String releaseKey, String action, String fromState,
        String toState, Integer trafficPercent, String operator, String reason, String detailsJson,
        String eventHash, LocalDateTime createdAt) {
        public ReleaseAudit {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(releaseId, "releaseId");
        }
    }

    private record AuditQuery(String sql, Object[] args) {
    }
}
