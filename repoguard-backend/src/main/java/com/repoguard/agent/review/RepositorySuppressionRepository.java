package com.repoguard.agent.review;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Tenant-scoped persistence for explicit repository suppression proposals and lifecycle events. */
@Repository
public class RepositorySuppressionRepository {

    private final JdbcTemplate jdbcTemplate;

    public RepositorySuppressionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    public StoredSuppression insert(
        long tenantId,
        String organization,
        String repository,
        String ruleId,
        String fileGlob,
        String symbol,
        String reason,
        String operator,
        LocalDateTime expiresAt,
        int previewHitCount
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                insert into review_repository_suppression
                    (tenant_id, organization, repository, rule_id, file_glob, symbol, reason, status,
                    operator, expires_at, preview_hit_count, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?, ?, ?, current_timestamp(6), current_timestamp(6))
                """, java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, tenantId);
            statement.setString(2, organization);
            statement.setString(3, repository);
            statement.setString(4, ruleId);
            statement.setString(5, fileGlob);
            statement.setString(6, symbol);
            statement.setString(7, reason);
            statement.setString(8, operator);
            statement.setTimestamp(9, Timestamp.valueOf(expiresAt));
            statement.setInt(10, Math.max(0, Math.min(500, previewHitCount)));
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("suppression proposal did not return an id");
        }
        audit(tenantId, id.longValue(), "PROPOSED", operator, reason);
        return find(tenantId, id.longValue());
    }

    public StoredSuppression find(long tenantId, long id) {
        List<StoredSuppression> rows = jdbcTemplate.query("""
            select id, tenant_id, organization, repository, rule_id, file_glob, symbol, reason, status,
                   operator, expires_at, preview_hit_count, hit_count, last_hit_at, created_at, updated_at
              from review_repository_suppression
             where tenant_id = ? and id = ?
            """, this::map, tenantId, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<StoredSuppression> list(long tenantId, String organization, String repository, int limit) {
        return jdbcTemplate.query("""
            select id, tenant_id, organization, repository, rule_id, file_glob, symbol, reason, status,
                   operator, expires_at, preview_hit_count, hit_count, last_hit_at, created_at, updated_at
              from review_repository_suppression
             where tenant_id = ? and lower(organization) = lower(?) and lower(repository) = lower(?)
             order by created_at desc, id desc
             limit ?
            """, this::map, tenantId, organization, repository, Math.max(1, Math.min(100, limit)));
    }

    public List<StoredSuppression> activeFor(long tenantId, String organization, String repository) {
        return jdbcTemplate.query("""
            select id, tenant_id, organization, repository, rule_id, file_glob, symbol, reason, status,
                   operator, expires_at, preview_hit_count, hit_count, last_hit_at, created_at, updated_at
              from review_repository_suppression
             where tenant_id = ? and lower(organization) = lower(?) and lower(repository) = lower(?)
               and status = 'ACTIVE' and expires_at > current_timestamp(6)
             order by id asc
             limit 128
            """, this::map, tenantId, organization, repository);
    }

    public StoredSuppression transition(
        long tenantId,
        long id,
        String expectedStatus,
        String nextStatus,
        String operator,
        String reason
    ) {
        int updated = jdbcTemplate.update("""
            update review_repository_suppression
               set status = ?, updated_at = current_timestamp(6)
             where tenant_id = ? and id = ? and status = ?
            """, nextStatus, tenantId, id, expectedStatus);
        if (updated == 1) {
            audit(tenantId, id, nextStatus, operator, reason);
        }
        return find(tenantId, id);
    }

    public int expireDue(long tenantId, int limit) {
        List<Long> ids = jdbcTemplate.query("""
            select id from review_repository_suppression
             where tenant_id = ? and status = 'ACTIVE' and expires_at <= current_timestamp(6)
             order by expires_at asc, id asc limit ?
            """, (rs, rowNum) -> rs.getLong(1), tenantId, Math.max(1, Math.min(100, limit)));
        for (Long id : ids) {
            transition(tenantId, id, "ACTIVE", "EXPIRED", "system", "suppression_expired");
        }
        return ids.size();
    }

    public void incrementHit(long tenantId, long id) {
        jdbcTemplate.update("""
            update review_repository_suppression
               set hit_count = hit_count + 1, last_hit_at = current_timestamp(6), updated_at = current_timestamp(6)
             where tenant_id = ? and id = ? and status = 'ACTIVE'
            """, tenantId, id);
    }

    private void audit(long tenantId, long suppressionId, String action, String operator, String reason) {
        jdbcTemplate.update("""
            insert into review_repository_suppression_audit
                (tenant_id, suppression_id, action, operator, reason, created_at)
            values (?, ?, ?, ?, ?, current_timestamp(6))
            """, tenantId, suppressionId, action, operator, reason);
    }

    private StoredSuppression map(ResultSet rs, int rowNum) throws SQLException {
        return new StoredSuppression(
            rs.getLong("id"),
            rs.getLong("tenant_id"),
            rs.getString("organization"),
            rs.getString("repository"),
            rs.getString("rule_id"),
            rs.getString("file_glob"),
            rs.getString("symbol"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("operator"),
            time(rs, "expires_at"),
            rs.getInt("preview_hit_count"),
            rs.getLong("hit_count"),
            time(rs, "last_hit_at"),
            time(rs, "created_at"),
            time(rs, "updated_at")
        );
    }

    private LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public record StoredSuppression(
        long id,
        long tenantId,
        String organization,
        String repository,
        String ruleId,
        String fileGlob,
        String symbol,
        String reason,
        String status,
        String operator,
        LocalDateTime expiresAt,
        int previewHitCount,
        long hitCount,
        LocalDateTime lastHitAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {

        public RepositoryPolicyDocument.SuppressionReference toReference() {
            return new RepositoryPolicyDocument.SuppressionReference(
                ruleId,
                fileGlob,
                symbol,
                reason,
                expiresAt.atOffset(ZoneOffset.UTC)
            );
        }

        public OffsetDateTime expiresAtOffset() {
            return expiresAt.atOffset(ZoneOffset.UTC);
        }
    }
}
