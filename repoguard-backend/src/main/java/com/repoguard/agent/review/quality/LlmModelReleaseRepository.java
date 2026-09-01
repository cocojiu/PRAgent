package com.repoguard.agent.review.quality;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Persistence boundary for tenant-scoped model release records. SQL stays here so the release
 * policy service only owns validation, quality gates, routing, and lifecycle decisions.
 */
@Repository
public class LlmModelReleaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public LlmModelReleaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public LlmModelReleaseDto save(
        long tenantId,
        LlmModelReleaseService.NormalizedRelease release,
        String state,
        int trafficPercent,
        String operator,
        String rollbackReason
    ) {
        List<Long> ids = jdbcTemplate.query(
            "select id from llm_model_release where tenant_id = ? and release_key = ?",
            (rs, rowNum) -> rs.getLong(1),
            tenantId,
            release.releaseKey()
        );
        String blockers = release.blockers().stream().collect(Collectors.joining(","));
        if (ids.isEmpty()) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                    insert into llm_model_release (
                        tenant_id, release_key, provider, model_name, prompt_version, context_version,
                        schema_version, dataset_id, dataset_version, dataset_fingerprint, state,
                        traffic_percent, quality_gate_passed, precision_rate, recall_rate, anchor_rate,
                        duplicate_rate, parse_failure_rate, p95_latency_ms, average_cost, total_tokens,
                        blockers, rollback_reason, created_by, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp(6), current_timestamp(6))
                    """, Statement.RETURN_GENERATED_KEYS);
                int index = 1;
                statement.setLong(index++, tenantId);
                statement.setString(index++, release.releaseKey());
                statement.setString(index++, release.provider());
                statement.setString(index++, release.modelName());
                statement.setString(index++, release.promptVersion());
                statement.setString(index++, release.contextVersion());
                statement.setString(index++, release.schemaVersion());
                statement.setString(index++, release.datasetId());
                statement.setString(index++, release.datasetVersion());
                statement.setString(index++, release.datasetFingerprint());
                statement.setString(index++, state);
                statement.setInt(index++, trafficPercent);
                statement.setBoolean(index++, release.qualityGatePassed());
                statement.setBigDecimal(index++, release.precisionRate());
                statement.setBigDecimal(index++, release.recallRate());
                statement.setBigDecimal(index++, release.anchorRate());
                statement.setBigDecimal(index++, release.duplicateRate());
                statement.setBigDecimal(index++, release.parseFailureRate());
                statement.setLong(index++, release.p95LatencyMs());
                statement.setBigDecimal(index++, release.averageCost());
                statement.setLong(index++, release.totalTokens());
                statement.setString(index++, blockers);
                statement.setString(index++, blankToNull(rollbackReason));
                statement.setString(index, operator);
                return statement;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("模型发布记录未返回主键");
            }
            return findById(tenantId, key.longValue());
        }
        jdbcTemplate.update("""
            update llm_model_release
               set provider = ?, model_name = ?, prompt_version = ?, context_version = ?, schema_version = ?,
                   dataset_id = ?, dataset_version = ?, dataset_fingerprint = ?, state = ?, traffic_percent = ?,
                   quality_gate_passed = ?, precision_rate = ?, recall_rate = ?, anchor_rate = ?, duplicate_rate = ?,
                   parse_failure_rate = ?, p95_latency_ms = ?, average_cost = ?, total_tokens = ?, blockers = ?,
                   rollback_reason = ?, created_by = ?, updated_at = current_timestamp(6)
             where tenant_id = ? and release_key = ?
            """,
            release.provider(),
            release.modelName(),
            release.promptVersion(),
            release.contextVersion(),
            release.schemaVersion(),
            release.datasetId(),
            release.datasetVersion(),
            release.datasetFingerprint(),
            state,
            trafficPercent,
            release.qualityGatePassed(),
            release.precisionRate(),
            release.recallRate(),
            release.anchorRate(),
            release.duplicateRate(),
            release.parseFailureRate(),
            release.p95LatencyMs(),
            release.averageCost(),
            release.totalTokens(),
            blockers,
            blankToNull(rollbackReason),
            operator,
            tenantId,
            release.releaseKey()
        );
        return findById(tenantId, ids.getFirst());
    }

    public LlmModelReleaseDto findById(long tenantId, long releaseId) {
        List<LlmModelReleaseDto> releases = jdbcTemplate.query(
            "select * from llm_model_release where tenant_id = ? and id = ?",
            (rs, rowNum) -> mapRelease(rs, rowNum),
            tenantId,
            releaseId
        );
        if (releases.size() != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型发布记录不存在");
        }
        return releases.getFirst();
    }

    public List<LlmModelReleaseDto> findAll(long tenantId) {
        return jdbcTemplate.query(
            "select * from llm_model_release where tenant_id = ? order by updated_at desc, id desc limit 50",
            this::mapRelease,
            tenantId
        );
    }

    public List<LlmModelReleaseDto> findByState(long tenantId, String state) {
        return jdbcTemplate.query(
            "select * from llm_model_release where tenant_id = ? and state = ? order by updated_at desc, id desc limit 10",
            this::mapRelease,
            tenantId,
            state
        );
    }

    public int markActiveReplaced(long tenantId) {
        return jdbcTemplate.update(
            """
            update llm_model_release
               set state = 'ROLLED_BACK', traffic_percent = 0,
                   rollback_reason = 'replaced by a newer active release'
             where tenant_id = ? and state = 'ACTIVE'
            """,
            tenantId
        );
    }

    public int rollback(long tenantId, long releaseId, String reason) {
        return jdbcTemplate.update(
            """
            update llm_model_release
               set state = 'ROLLED_BACK', traffic_percent = 0,
                   rollback_reason = ?, updated_at = current_timestamp(6)
             where tenant_id = ? and id = ? and state <> 'ROLLED_BACK'
            """,
            reason,
            tenantId,
            releaseId
        );
    }

    private LlmModelReleaseDto mapRelease(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LlmModelReleaseDto(
            rs.getLong("id"),
            rs.getString("release_key"),
            rs.getString("provider"),
            rs.getString("model_name"),
            rs.getString("prompt_version"),
            rs.getString("context_version"),
            rs.getString("schema_version"),
            rs.getString("dataset_id"),
            rs.getString("dataset_version"),
            rs.getString("dataset_fingerprint"),
            rs.getString("state"),
            rs.getInt("traffic_percent"),
            rs.getBoolean("quality_gate_passed"),
            rs.getBigDecimal("precision_rate"),
            rs.getBigDecimal("recall_rate"),
            rs.getBigDecimal("anchor_rate"),
            rs.getBigDecimal("duplicate_rate"),
            rs.getBigDecimal("parse_failure_rate"),
            rs.getLong("p95_latency_ms"),
            rs.getBigDecimal("average_cost"),
            rs.getLong("total_tokens"),
            parseBlockers(rs.getString("blockers")),
            rs.getString("rollback_reason"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private List<String> parseBlockers(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
