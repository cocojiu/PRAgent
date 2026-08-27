package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.EnterpriseIdentityBindingRequest;
import com.repoguard.agent.dto.EnterpriseTenantCreateRequest;
import com.repoguard.agent.dto.EnterpriseTenantMembershipRequest;
import com.repoguard.agent.dto.EnterpriseTenantRepositoryRequest;
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

        var created = service.create(new EnterpriseTenantCreateRequest(" acme ", " Acme Corp ", 12L));

        assertThat(created.tenantId()).isEqualTo(8L);
        assertThat(created.tenantKey()).isEqualTo("acme");
        assertThat(created.displayName()).isEqualTo("Acme Corp");
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

    private void activeTenant(String tenantKey, long tenantId) {
        when(jdbcTemplate.query(
            org.mockito.ArgumentMatchers.contains("select id from tenant"),
            org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),
            eq(tenantKey)
        )).thenReturn(List.of(tenantId));
    }
}
