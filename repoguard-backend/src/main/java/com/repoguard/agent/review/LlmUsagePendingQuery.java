package com.repoguard.agent.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmUsageAccountingDto.PendingUsage;
import com.repoguard.agent.dto.LlmUsageAccountingDto.PendingUsagePage;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import java.time.LocalDate;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Read-only keyset traversal; every query independently enforces its tenant boundary. */
@Component
@ApiRuntimeEnabled
public final class LlmUsagePendingQuery {
    static final String PENDING_PREDICATE = """
        (status in ('RESERVED','UNKNOWN') or (status='LEGACY' and (
            json_unquote(json_extract(usage_json,'$.legacyUnknown')) in ('1','true')
            or json_unquote(json_extract(usage_json,'$.legacyUnpriced')) in ('1','true'))))
        """;
    private static final String SELECT = """
        select call_key,usage_month,task_id,attempt_id,operation,provider,model_name,status,
            accounted_tokens,accounted_cost,started_at from llm_usage_call
        where tenant_id=? and
        """ + PENDING_PREDICATE;
    private static final RowMapper<PendingUsage> MAPPER = (row, index) -> new PendingUsage(
        row.getString("call_key"), row.getObject("usage_month", LocalDate.class),
        row.getObject("task_id", Long.class), row.getObject("attempt_id", Long.class),
        row.getString("operation"), row.getString("provider"), row.getString("model_name"),
        row.getString("status"), row.getLong("accounted_tokens"), row.getBigDecimal("accounted_cost"),
        row.getObject("started_at", LocalDateTime.class).atOffset(ZoneOffset.UTC));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TenantProperties tenancy;

    LlmUsagePendingQuery(JdbcTemplate jdbc, ObjectMapper json) {
        this(jdbc, json, new TenantProperties());
    }

    @Autowired
    public LlmUsagePendingQuery(JdbcTemplate jdbc, ObjectMapper json, TenantProperties tenancy) {
        this.jdbc = jdbc;
        this.json = json;
        this.tenancy = tenancy;
    }

    public PendingUsagePage page(int limit, String month, String status, String cursor) {
        if (tenancy.isEnabled() && TenantContext.currentTenantId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Tenant context is required for accounting diagnostics");
        }
        return page(TenantContext.currentTenantIdOrDefault(), limit, month, status, cursor);
    }

    PendingUsagePage page(long tenant, int limit, String month, String status, String cursor) {
        if (limit < 1 || limit > 100) throw invalid();
        LocalDate monthStart = monthStart(month);
        if (status != null && !List.of("RESERVED", "UNKNOWN", "LEGACY").contains(status)) throw invalid();
        Cursor after = decode(cursor, tenant, month, status);
        StringBuilder sql = new StringBuilder(SELECT);
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenant);
        if (monthStart != null) {
            sql.append(" and usage_month=?");
            arguments.add(monthStart);
        }
        if (status != null) {
            sql.append(" and status=?");
            arguments.add(status);
        }
        if (after != null) {
            LocalDateTime started = LocalDateTime.parse(after.startedAt());
            sql.append(" and (started_at>? or (started_at=? and call_key>?))");
            arguments.add(started);
            arguments.add(started);
            arguments.add(after.callKey());
        }
        sql.append(" order by started_at,call_key limit ?");
        arguments.add(limit + 1);
        List<PendingUsage> rows = jdbc.query(sql.toString(), MAPPER, arguments.toArray());
        boolean hasMore = rows.size() > limit;
        List<PendingUsage> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        String nextCursor = hasMore ? encode(new Cursor(1, tenant, month, status,
            items.getLast().startedAt().toLocalDateTime().toString(), items.getLast().callKey())) : null;
        return new PendingUsagePage(items, nextCursor, hasMore);
    }

    private static LocalDate monthStart(String month) {
        if (month == null) return null;
        try {
            if (!month.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw invalid();
            YearMonth parsed = YearMonth.parse(month);
            if (parsed.getYear() < 1000) throw invalid();
            return parsed.atDay(1);
        } catch (java.time.DateTimeException failure) {
            throw invalid();
        }
    }

    private Cursor decode(String cursor, long tenant, String month, String status) {
        if (cursor == null) return null;
        if (cursor.isBlank() || cursor.length() > 1024) throw invalid();
        try {
            Cursor value = json.readValue(Base64.getUrlDecoder().decode(cursor), Cursor.class);
            if (value == null || value.version() != 1 || value.tenant() != tenant
                || !Objects.equals(value.month(), month) || !Objects.equals(value.status(), status)
                || value.callKey() == null || value.callKey().isBlank() || value.callKey().length() > 128
                || value.startedAt() == null) throw invalid();
            LocalDateTime started = LocalDateTime.parse(value.startedAt());
            if (started.getYear() < 1000 || started.getYear() > 9999) throw invalid();
            return value;
        } catch (IOException | IllegalArgumentException | java.time.DateTimeException failure) {
            throw invalid();
        }
    }

    private String encode(Cursor cursor) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(cursor));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode accounting page cursor", failure);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "Invalid accounting page filters or cursor");
    }

    private record Cursor(int version, long tenant, String month, String status, String startedAt, String callKey) { }
}
