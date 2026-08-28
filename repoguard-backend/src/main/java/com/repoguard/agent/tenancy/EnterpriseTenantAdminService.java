package com.repoguard.agent.tenancy;

import com.repoguard.agent.cache.ClusterCacheInvalidationPublisher;
import com.repoguard.agent.cache.ClusterCacheInvalidationType;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.EnterpriseIdentityBindingRequest;
import com.repoguard.agent.dto.EnterpriseTenantCreateRequest;
import com.repoguard.agent.dto.EnterpriseTenantDto;
import com.repoguard.agent.dto.EnterpriseTenantMembershipRequest;
import com.repoguard.agent.dto.EnterpriseTenantRepositoryRequest;
import com.repoguard.agent.dto.EnterpriseTenantStatusRequest;
import com.repoguard.agent.dto.PageResponse;
import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EnterpriseTenantAdminService {

    private static final long DEFAULT_TENANT_ID = 1L;
    private static final RowMapper<EnterpriseTenantDto> TENANT_ROW_MAPPER = (resultSet, rowNum) ->
        new EnterpriseTenantDto(
            resultSet.getLong("id"),
            resultSet.getString("tenant_key"),
            resultSet.getString("display_name"),
            resultSet.getString("status"),
            resultSet.getLong("status_version"),
            resultSet.getString("status_reason"),
            localDateTime(resultSet.getTimestamp("status_changed_at")),
            localDateTime(resultSet.getTimestamp("created_at")),
            localDateTime(resultSet.getTimestamp("updated_at"))
        );
    private static final String TENANT_COLUMNS = """
        select id, tenant_key, display_name, status, status_version, status_reason,
               status_changed_at, created_at, updated_at
          from tenant
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ClusterCacheInvalidationPublisher cacheInvalidationPublisher;

    @Autowired
    public EnterpriseTenantAdminService(
        JdbcTemplate jdbcTemplate,
        ObjectProvider<ClusterCacheInvalidationPublisher> cacheInvalidationPublisherProvider
    ) {
        this(
            jdbcTemplate,
            Objects.requireNonNull(cacheInvalidationPublisherProvider, "cacheInvalidationPublisherProvider")
                .getIfAvailable()
        );
    }

    public EnterpriseTenantAdminService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, (ClusterCacheInvalidationPublisher) null);
    }

    EnterpriseTenantAdminService(
        JdbcTemplate jdbcTemplate,
        ClusterCacheInvalidationPublisher cacheInvalidationPublisher
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.cacheInvalidationPublisher = cacheInvalidationPublisher;
    }

    @Transactional
    public EnterpriseTenantDto create(EnterpriseTenantCreateRequest request) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                    "insert into tenant (tenant_key, display_name, status) values (?, ?, 'ACTIVE')",
                    Statement.RETURN_GENERATED_KEYS
                );
                statement.setString(1, request.tenantKey().trim());
                statement.setString(2, request.displayName().trim());
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "Tenant key already exists");
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Created tenant id was not returned");
        }
        long tenantId = key.longValue();
        addMembership(tenantId, request.initialAdminUserId(), "ADMIN", true);
        initializeTenantConfig(tenantId);
        return tenant(request.tenantKey());
    }

    @Transactional(readOnly = true)
    public PageResponse<EnterpriseTenantDto> list(int page, int pageSize, String status) {
        String normalizedStatus = normalizeOptionalStatus(status);
        long offset = Math.multiplyExact((long) page - 1L, pageSize);
        Long total = jdbcTemplate.queryForObject(
            "select count(*) from tenant where (? is null or status = ?)",
            Long.class,
            normalizedStatus,
            normalizedStatus
        );
        List<EnterpriseTenantDto> tenants = jdbcTemplate.query(
            TENANT_COLUMNS + """
             where (? is null or status = ?)
             order by id
             limit ? offset ?
            """,
            TENANT_ROW_MAPPER,
            normalizedStatus,
            normalizedStatus,
            pageSize,
            offset
        );
        return new PageResponse<>(tenants, total == null ? 0L : total);
    }

    @Transactional(readOnly = true)
    public EnterpriseTenantDto get(String tenantKey) {
        return tenant(tenantKey);
    }

    @Transactional
    public EnterpriseTenantDto updateStatus(
        String tenantKey,
        EnterpriseTenantStatusRequest request
    ) {
        EnterpriseTenantDto current = tenant(tenantKey);
        String expectedStatus = normalizeStatus(request.expectedStatus());
        String targetStatus = normalizeStatus(request.targetStatus());
        if (!current.status().equals(expectedStatus)
            || !current.statusVersion().equals(request.expectedVersion())) {
            throw new BusinessException(
                ErrorCode.CONFLICT,
                "Tenant status changed; reload the tenant and retry"
            );
        }
        if (expectedStatus.equals(targetStatus)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Target tenant status must be different");
        }
        if (current.tenantId() == DEFAULT_TENANT_ID && "SUSPENDED".equals(targetStatus)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Default tenant cannot be suspended");
        }
        int updated = jdbcTemplate.update(
            """
            update tenant
               set status = ?,
                   status_version = status_version + 1,
                   status_reason = ?,
                   status_changed_at = current_timestamp(6),
                   updated_at = current_timestamp(6)
             where id = ?
               and status = ?
               and status_version = ?
            """,
            targetStatus,
            request.reason().trim(),
            current.tenantId(),
            expectedStatus,
            request.expectedVersion()
        );
        if (updated != 1) {
            throw new BusinessException(
                ErrorCode.CONFLICT,
                "Tenant status changed; reload the tenant and retry"
            );
        }
        if (cacheInvalidationPublisher != null) {
            cacheInvalidationPublisher.publish(
                current.tenantId(),
                ClusterCacheInvalidationType.TENANT_LIFECYCLE,
                LocalDate.now()
            );
        }
        return tenant(tenantKey);
    }

    @Transactional
    public void putMembership(String tenantKey, EnterpriseTenantMembershipRequest request) {
        long tenantId = tenantId(tenantKey);
        if (request.defaultTenant()) {
            jdbcTemplate.update(
                "update tenant_membership set default_tenant = 0 where user_id = ?",
                request.userId()
            );
        }
        addMembership(tenantId, request.userId(), request.role(), request.defaultTenant());
    }

    @Transactional
    public void putRepository(String tenantKey, EnterpriseTenantRepositoryRequest request) {
        long tenantId = tenantId(tenantKey);
        try {
            int updated = jdbcTemplate.update(
                """
                update tenant_repository
                   set github_installation_id = ?, status = 'ACTIVE', updated_at = now()
                 where tenant_id = ?
                   and lower(organization) = lower(?)
                   and lower(repository) = lower(?)
                """,
                request.githubInstallationId(),
                tenantId,
                request.organization().trim(),
                request.repository().trim()
            );
            if (updated == 0) {
                jdbcTemplate.update(
                    """
                    insert into tenant_repository
                        (tenant_id, organization, repository, github_installation_id, status)
                    values (?, ?, ?, ?, 'ACTIVE')
                    """,
                    tenantId,
                    request.organization().trim(),
                    request.repository().trim(),
                    request.githubInstallationId()
                );
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                ErrorCode.CONFLICT,
                "Repository or GitHub installation is already assigned to another tenant"
            );
        }
    }

    @Transactional
    public void putIdentity(String tenantKey, EnterpriseIdentityBindingRequest request) {
        long tenantId = tenantId(tenantKey);
        validateIssuer(request.issuer());
        try {
            int updated = jdbcTemplate.update(
                """
                update enterprise_identity
                   set issuer = ?, subject = ?, status = 'ACTIVE', updated_at = now()
                 where tenant_id = ? and user_id = ?
                """,
                request.issuer().trim(),
                request.subject().trim(),
                tenantId,
                request.userId()
            );
            if (updated == 0) {
                jdbcTemplate.update(
                    """
                    insert into enterprise_identity (tenant_id, user_id, issuer, subject, status)
                    values (?, ?, ?, ?, 'ACTIVE')
                    """,
                    tenantId,
                    request.userId(),
                    request.issuer().trim(),
                    request.subject().trim()
                );
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "Enterprise identity is already bound");
        }
    }

    private void addMembership(long tenantId, long userId, String role, boolean defaultTenant) {
        Integer userCount = jdbcTemplate.queryForObject(
            "select count(*) from user_account where id = ? and status = 'ACTIVE'",
            Integer.class,
            userId
        );
        if (userCount == null || userCount != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Active user account was not found");
        }
        jdbcTemplate.update(
            """
            insert into tenant_membership (tenant_id, user_id, role, default_tenant)
            values (?, ?, ?, ?)
            on duplicate key update
                role = values(role),
                default_tenant = values(default_tenant),
                updated_at = now()
            """,
            tenantId,
            userId,
            role,
            defaultTenant
        );
    }

    private long tenantId(String tenantKey) {
        if (!StringUtils.hasText(tenantKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Tenant key is required");
        }
        List<Long> ids = jdbcTemplate.query(
            "select id from tenant where tenant_key = ? and status = 'ACTIVE'",
            (resultSet, rowNum) -> resultSet.getLong(1),
            tenantKey.trim()
        );
        if (ids.size() != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Active tenant was not found");
        }
        return ids.getFirst();
    }

    private EnterpriseTenantDto tenant(String tenantKey) {
        if (!StringUtils.hasText(tenantKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Tenant key is required");
        }
        List<EnterpriseTenantDto> tenants = jdbcTemplate.query(
            TENANT_COLUMNS + " where tenant_key = ?",
            TENANT_ROW_MAPPER,
            tenantKey.trim()
        );
        if (tenants.size() != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Tenant was not found");
        }
        return tenants.getFirst();
    }

    private String normalizeOptionalStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        return normalizeStatus(status);
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Tenant status is required");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(normalized) && !"SUSPENDED".equals(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported tenant status");
        }
        return normalized;
    }

    private static LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private void validateIssuer(String issuer) {
        try {
            URI uri = URI.create(issuer.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("issuer must use HTTPS");
            }
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Enterprise issuer must be a valid HTTPS URI");
        }
    }

    private void initializeTenantConfig(long tenantId) {
        jdbcTemplate.update(
            """
            insert into integration_config
                (tenant_id, provider, status, base_url, token_value, default_owner, default_repo,
                 last_checked_at, last_error, created_at, updated_at)
            values (?, 'GITHUB', 'NOT_CONFIGURED', 'https://api.github.com', null, null, null,
                    null, null, now(), now())
            """,
            tenantId
        );
        jdbcTemplate.update(
            """
            insert into review_policy_config
                (tenant_id, llm_enabled, llm_provider, model_name, base_url, api_key_value,
                 timeout_seconds, temperature, max_tokens, fallback_to_rules, worker_concurrency,
                 chunk_file_threshold, chunk_line_threshold, chunk_max_files, chunk_max_lines,
                 input_token_price_per_million, output_token_price_per_million, created_at, updated_at)
            select ?, llm_enabled, llm_provider, model_name, base_url, null,
                   timeout_seconds, temperature, max_tokens, fallback_to_rules, worker_concurrency,
                   chunk_file_threshold, chunk_line_threshold, chunk_max_files, chunk_max_lines,
                   input_token_price_per_million, output_token_price_per_million, now(), now()
              from review_policy_config where tenant_id = 1 order by id limit 1
            """,
            tenantId
        );
        jdbcTemplate.update(
            """
            insert into system_settings_config
                (tenant_id, system_name, language, timezone, retention_days, max_diff_lines,
                 auto_comment, auto_retry, github_comment, high_risk_pr, failed_task,
                 notification_email, webhook_signature, secret_masking, public_repo_allowed,
                 token_ttl_days, created_at, updated_at)
            select ?, system_name, language, timezone, retention_days, max_diff_lines,
                   auto_comment, auto_retry, github_comment, high_risk_pr, failed_task,
                   notification_email, webhook_signature, secret_masking, public_repo_allowed,
                   token_ttl_days, now(), now()
              from system_settings_config where tenant_id = 1 order by id limit 1
            """,
            tenantId
        );
        jdbcTemplate.update(
            """
            insert into review_rule_config
                (tenant_id, id, detector_version, config_version, policy_version, rule_name, scope,
                 applicable_languages, file_patterns, severity, status, confidence, enforcement_mode,
                 description, positive_example, false_positive_guidance, sort_order, created_at, updated_at)
            select ?, id, detector_version, config_version, policy_version, rule_name, scope,
                   applicable_languages, file_patterns, severity, status, confidence, enforcement_mode,
                   description, positive_example, false_positive_guidance, sort_order, now(), now()
              from review_rule_config where tenant_id = 1
            """,
            tenantId
        );
        jdbcTemplate.update(
            """
            insert into review_quality_baseline_snapshot
                (tenant_id, snapshot_key, source_version, refreshed_version, baseline_payload, calculated_at)
            values (?, 'GLOBAL', 1, 0, null, null)
            """,
            tenantId
        );
    }
}
