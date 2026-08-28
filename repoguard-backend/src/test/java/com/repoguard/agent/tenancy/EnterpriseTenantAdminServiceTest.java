package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.cache.ClusterCacheInvalidationPublisher;
import com.repoguard.agent.cache.ClusterCacheInvalidationType;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.EnterpriseIdentityBindingRequest;
import com.repoguard.agent.dto.EnterpriseTenantCreateRequest;
import com.repoguard.agent.dto.EnterpriseTenantMembershipRequest;
import com.repoguard.agent.dto.EnterpriseTenantRepositoryRequest;
import com.repoguard.agent.dto.EnterpriseTenantDto;
import com.repoguard.agent.dto.EnterpriseTenantStatusRequest;
import com.repoguard.agent.dto.PageResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

class EnterpriseTenantAdminServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EnterpriseTenantAdminService service = new EnterpriseTenantAdminService(jdbcTemplate);

    @Test
    void createsTenantAdminAndTenantLocalConfiguration() {
        when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
            .thenAnswer(invocation -> {
                KeyHolder holder = invocation.getArgument(1);
                holder.getKeyList().add(Map.of("GENERATED_KEY", 8L));
                return 1;
            });
        when(jdbcTemplate.queryForObject(
            anyString(),
            eq(Integer.class),
            eq(12L)
        )).thenReturn(1);
        tenant("acme", tenantDto(8L, "acme", "ACTIVE", 1L));

        var created = service.create(new EnterpriseTenantCreateRequest(" acme ", " Acme Corp ", 12L));

        assertThat(created.tenantId()).isEqualTo(8L);
        assertThat(created.tenantKey()).isEqualTo("acme");
        assertThat(created.displayName()).isEqualTo("Acme Corp");
        assertThat(created.status()).isEqualTo("ACTIVE");
        verify(jdbcTemplate).update(
            org.mockito.ArgumentMatchers.contains("insert into tenant_membership"),
            eq(8L),
            eq(12L),
            eq("ADMIN"),
            eq(true)
        );
        verify(jdbcTemplate).update(
            org.mockito.ArgumentMatchers.contains("insert into review_quality_baseline_snapshot"),
            eq(8L)
        );
    }

    @Test
    void mapsDuplicateTenantKeyToConflict() {
        when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
            .thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.create(
            new EnterpriseTenantCreateRequest("acme", "Acme", 12L)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT)
        );
    }

    @Test
    void changingDefaultMembershipClearsPreviousDefaultThenUpsertsRole() {
        activeTenant("acme", 8L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(12L))).thenReturn(1);

        service.putMembership(
            "acme",
            new EnterpriseTenantMembershipRequest(12L, "VIEWER", true)
        );

        verify(jdbcTemplate).update(
            org.mockito.ArgumentMatchers.contains("set default_tenant = 0"),
            eq(12L)
        );
        verify(jdbcTemplate).update(
            org.mockito.ArgumentMatchers.contains("insert into tenant_membership"),
            eq(8L),
            eq(12L),
            eq("VIEWER"),
            eq(true)
        );
    }

    @Test
    void insertsRepositoryBindingWhenNoTenantLocalRowExists() {
        activeTenant("acme", 8L);
        when(jdbcTemplate.update(
            org.mockito.ArgumentMatchers.contains("update tenant_repository"),
            eq(77L),
            eq(8L),
            eq("openai"),
            eq("repoguard")
        )).thenReturn(0);

        service.putRepository(
            "acme",
            new EnterpriseTenantRepositoryRequest(" openai ", " repoguard ", 77L)
        );

        verify(jdbcTemplate).update(
            org.mockito.ArgumentMatchers.contains("insert into tenant_repository"),
            eq(8L),
            eq("openai"),
            eq("repoguard"),
            eq(77L)
        );
    }

    @Test
    void rejectsNonHttpsEnterpriseIssuer() {
        activeTenant("acme", 8L);

        assertThatThrownBy(() -> service.putIdentity(
            "acme",
            new EnterpriseIdentityBindingRequest(12L, "http://identity.example.com", "subject")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
        );
    }

    @Test
    void listsTenantsWithBoundedPageAndOptionalStatusFilter() {
        EnterpriseTenantDto tenant = tenantDto(8L, "acme", "ACTIVE", 1L);
        when(jdbcTemplate.queryForObject(
            anyString(),
            eq(Long.class),
            isNull(),
            isNull()
        )).thenReturn(1L);
        when(jdbcTemplate.query(
            org.mockito.ArgumentMatchers.contains("status_version"),
            org.mockito.ArgumentMatchers.<RowMapper<EnterpriseTenantDto>>any(),
            isNull(),
            isNull(),
            eq(20),
            eq(0L)
        )).thenReturn(List.of(tenant));

        PageResponse<EnterpriseTenantDto> result = service.list(1, 20, null);

        assertThat(result.items()).containsExactly(tenant);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void statusTransitionUsesCompareAndSetAndInvalidatesTenantCaches() {
        ClusterCacheInvalidationPublisher publisher = mock(ClusterCacheInvalidationPublisher.class);
        EnterpriseTenantAdminService lifecycleService =
            new EnterpriseTenantAdminService(jdbcTemplate, publisher);
        EnterpriseTenantDto active = tenantDto(8L, "acme", "ACTIVE", 3L);
        EnterpriseTenantDto suspended = tenantDto(8L, "acme", "SUSPENDED", 4L);
        when(jdbcTemplate.query(
            org.mockito.ArgumentMatchers.contains("status_version"),
            org.mockito.ArgumentMatchers.<RowMapper<EnterpriseTenantDto>>any(),
            eq("acme")
        )).thenReturn(List.of(active)).thenReturn(List.of(suspended));
        when(jdbcTemplate.update(
            org.mockito.ArgumentMatchers.contains("update tenant"),
            eq("SUSPENDED"),
            eq("maintenance"),
            eq(8L),
            eq("ACTIVE"),
            eq(3L)
        )).thenReturn(1);

        EnterpriseTenantDto result = lifecycleService.updateStatus(
            "acme",
            new EnterpriseTenantStatusRequest("ACTIVE", "SUSPENDED", 3L, " maintenance ")
        );

        assertThat(result.status()).isEqualTo("SUSPENDED");
        assertThat(result.statusVersion()).isEqualTo(4L);
        verify(publisher).publish(
            eq(8L),
            eq(ClusterCacheInvalidationType.TENANT_LIFECYCLE),
            any()
        );
    }

    @Test
    void rejectsStaleStatusVersionBeforeWriting() {
        tenant("acme", tenantDto(8L, "acme", "ACTIVE", 4L));

        assertThatThrownBy(() -> service.updateStatus(
            "acme",
            new EnterpriseTenantStatusRequest("ACTIVE", "SUSPENDED", 3L, "maintenance")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT)
        );
    }

    @Test
    void defaultTenantCannotBeSuspended() {
        tenant("default", tenantDto(1L, "default", "ACTIVE", 1L));

        assertThatThrownBy(() -> service.updateStatus(
            "default",
            new EnterpriseTenantStatusRequest("ACTIVE", "SUSPENDED", 1L, "maintenance")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
        );
    }

    private void activeTenant(String tenantKey, long tenantId) {
        when(jdbcTemplate.query(
            org.mockito.ArgumentMatchers.contains("select id from tenant"),
            org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),
            eq(tenantKey)
        )).thenReturn(List.of(tenantId));
    }

    private void tenant(String tenantKey, EnterpriseTenantDto tenant) {
        when(jdbcTemplate.query(
            org.mockito.ArgumentMatchers.contains("status_version"),
            org.mockito.ArgumentMatchers.<RowMapper<EnterpriseTenantDto>>any(),
            eq(tenantKey)
        )).thenReturn(List.of(tenant));
    }

    private EnterpriseTenantDto tenantDto(
        long tenantId,
        String tenantKey,
        String status,
        long statusVersion
    ) {
        LocalDateTime now = LocalDateTime.parse("2026-08-28T12:00:00");
        return new EnterpriseTenantDto(
            tenantId,
            tenantKey,
            "Acme Corp",
            status,
            statusVersion,
            null,
            now,
            now,
            now
        );
    }
}
