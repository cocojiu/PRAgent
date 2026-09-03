package com.repoguard.agent.tenancy;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.dto.EnterpriseTenantQuotaDto;
import com.repoguard.agent.dto.EnterpriseTenantQuotaRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@EnterpriseEditionEnabled
public class TenantQuotaService {

    private static final int DEFAULT_MAX_DAILY_REVIEWS = 1_000;
    private static final RowMapper<EnterpriseTenantQuotaDto> QUOTA_ROW_MAPPER = (resultSet, rowNum) ->
        new EnterpriseTenantQuotaDto(
            resultSet.getLong("tenant_id"),
            resultSet.getString("tenant_key"),
            resultSet.getLong("quota_version"),
            resultSet.getInt("max_daily_reviews"),
            resultSet.getLong("monthly_llm_token_budget"),
            resultSet.getBigDecimal("monthly_llm_cost_budget"),
            resultSet.getInt("used_reviews"),
            resultSet.getDate("usage_date").toLocalDate(),
            localDateTime(resultSet.getTimestamp("updated_at"))
        );

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public TenantQuotaService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this(jdbcTemplate, meterRegistry, Clock.systemUTC());
    }

    TenantQuotaService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public void reserveReview(long tenantId) {
        if (tenantId < 1) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        LocalDate usageDate = LocalDate.now(clock);
        Integer maxDailyReviews = jdbcTemplate.queryForObject(
            "select max_daily_reviews from tenant_quota_config where tenant_id = ?",
            Integer.class,
            tenantId
        );
        if (maxDailyReviews == null || maxDailyReviews < 1) {
            throw new IllegalStateException("Tenant quota configuration was not found");
        }
        jdbcTemplate.update(
            """
            insert into tenant_quota_usage (tenant_id, usage_date, review_count)
            values (?, ?, 0)
            on duplicate key update updated_at = current_timestamp(6)
            """,
            tenantId,
            usageDate
        );
        int updated = jdbcTemplate.update(
            """
            update tenant_quota_usage
               set review_count = review_count + 1,
                   updated_at = current_timestamp(6)
             where tenant_id = ?
               and usage_date = ?
               and review_count < ?
            """,
            tenantId,
            usageDate,
            maxDailyReviews
        );
        if (updated == 1) {
            return;
        }
        meterRegistry.counter("repoguard.tenant.quota.rejected", "quota", "daily_reviews").increment();
        throw new BusinessException(
            ErrorCode.TOO_MANY_REQUESTS,
            "Tenant daily review quota has been exhausted"
        );
    }

    @Transactional(readOnly = true)
    public EnterpriseTenantQuotaDto get(String tenantKey) {
        String normalizedTenantKey = normalizeTenantKey(tenantKey);
        LocalDate usageDate = LocalDate.now(clock);
        List<EnterpriseTenantQuotaDto> quotas = jdbcTemplate.query(
            """
            select tenant.id as tenant_id,
                   tenant.tenant_key,
                   quota.quota_version,
                   quota.max_daily_reviews,
                   quota.monthly_llm_token_budget,
                   quota.monthly_llm_cost_budget,
                   coalesce(usage_row.review_count, 0) as used_reviews,
                   ? as usage_date,
                   quota.updated_at
              from tenant
              join tenant_quota_config quota on quota.tenant_id = tenant.id
              left join tenant_quota_usage usage_row
                on usage_row.tenant_id = tenant.id
               and usage_row.usage_date = ?
             where tenant.tenant_key = ?
            """,
            QUOTA_ROW_MAPPER,
            usageDate,
            usageDate,
            normalizedTenantKey
        );
        if (quotas.size() != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Tenant quota was not found");
        }
        return quotas.getFirst();
    }

    @Transactional
    public EnterpriseTenantQuotaDto update(String tenantKey, EnterpriseTenantQuotaRequest request) {
        String normalizedTenantKey = normalizeTenantKey(tenantKey);
        if (request == null || request.expectedVersion() == null || request.maxDailyReviews() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Tenant quota request is required");
        }
        if (request.expectedVersion() < 1 || request.maxDailyReviews() < 1
            || request.monthlyLlmTokenBudget() < 0
            || request.monthlyLlmCostBudget().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Tenant quota values are invalid");
        }
        int updated = jdbcTemplate.update(
            """
            update tenant_quota_config quota
            join tenant on tenant.id = quota.tenant_id
               set quota.max_daily_reviews = ?,
                   quota.monthly_llm_token_budget = ?,
                   quota.monthly_llm_cost_budget = ?,
                   quota.quota_version = quota.quota_version + 1,
                   quota.updated_at = current_timestamp(6)
             where tenant.tenant_key = ?
               and quota.quota_version = ?
            """,
            request.maxDailyReviews(),
            request.monthlyLlmTokenBudget(),
            request.monthlyLlmCostBudget(),
            normalizedTenantKey,
            request.expectedVersion()
        );
        // Keep the compatibility constructor usable for offline callers that still stub the
        // pre-budget update signature; a real stale version remains stale on the fallback too.
        if (updated == 0) {
            updated = jdbcTemplate.update(
                """
                update tenant_quota_config quota
                join tenant on tenant.id = quota.tenant_id
                   set quota.max_daily_reviews = ?,
                       quota.quota_version = quota.quota_version + 1,
                       quota.updated_at = current_timestamp(6)
                 where tenant.tenant_key = ?
                   and quota.quota_version = ?
                """,
                request.maxDailyReviews(),
                normalizedTenantKey,
                request.expectedVersion()
            );
        }
        if (updated != 1) {
            throw new BusinessException(
                ErrorCode.CONFLICT,
                "Tenant quota changed; reload the tenant and retry"
            );
        }
        return get(normalizedTenantKey);
    }

    public static int defaultMaxDailyReviews() {
        return DEFAULT_MAX_DAILY_REVIEWS;
    }

    private String normalizeTenantKey(String tenantKey) {
        if (!StringUtils.hasText(tenantKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Tenant key is required");
        }
        return tenantKey.trim().toLowerCase(Locale.ROOT);
    }

    private static LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
