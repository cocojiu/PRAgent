package com.repoguard.agent.tenancy;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.EnterpriseIdentityBindingRequest;
import com.repoguard.agent.dto.EnterpriseTenantCreateRequest;
import com.repoguard.agent.dto.EnterpriseTenantDto;
import com.repoguard.agent.dto.EnterpriseTenantMembershipRequest;
import com.repoguard.agent.dto.EnterpriseTenantRepositoryRequest;
import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EnterpriseTenantAdminService {

    private final JdbcTemplate jdbcTemplate;

    public EnterpriseTenantAdminService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
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
        return new EnterpriseTenantDto(tenantId, request.tenantKey().trim(), request.displayName().trim());
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
