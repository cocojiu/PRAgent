package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.EnterpriseIdentityBindingRequest;
import com.repoguard.agent.dto.EnterpriseTenantCreateRequest;
import com.repoguard.agent.dto.EnterpriseTenantMembershipRequest;
import com.repoguard.agent.dto.EnterpriseTenantRepositoryRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

class EnterpriseTenantAdminServiceEdgeCaseTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EnterpriseTenantAdminService service = new EnterpriseTenantAdminService(jdbcTemplate);

    @Test
    void preparesTrimmedTenantInsertAndRejectsMissingGeneratedId() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS)))
            .thenReturn(statement);
        when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
            .thenAnswer(invocation -> {
                PreparedStatementCreator creator = invocation.getArgument(0);
                creator.createPreparedStatement(connection);
                return 1;
            });

        assertThatThrownBy(() -> service.create(
            new EnterpriseTenantCreateRequest(" acme ", " Acme Corp ", 12L)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tenant id");

        verify(statement).setString(1, "acme");
        verify(statement).setString(2, "Acme Corp");
    }

    @Test
    void insertsEnterpriseIdentityForActiveTenant() {
        activeTenant("acme", 8L);
        when(jdbcTemplate.update(
            contains("update enterprise_identity"),
            eq("https://identity.example.com"),
            eq("subject-12"),
            eq(8L),
            eq(12L)
        )).thenReturn(0);

        service.putIdentity(
            "acme",
            new EnterpriseIdentityBindingRequest(
                12L,
                " https://identity.example.com ",
                " subject-12 "
            )
        );

        verify(jdbcTemplate).update(
            contains("insert into enterprise_identity"),
            eq(8L),
            eq(12L),
            eq("https://identity.example.com"),
            eq("subject-12")
        );
    }

    @Test
    void updatesExistingIdentityWithoutInsertingAnotherRow() {
        activeTenant("acme", 8L);
        when(jdbcTemplate.update(
            contains("update enterprise_identity"),
            eq("https://identity.example.com"),
            eq("subject-12"),
            eq(8L),
            eq(12L)
        )).thenReturn(1);

        service.putIdentity(
            "acme",
            new EnterpriseIdentityBindingRequest(
                12L,
                "https://identity.example.com",
                "subject-12"
            )
        );

        verify(jdbcTemplate, never()).update(
            contains("insert into enterprise_identity"),
            any(),
            any(),
            any(),
            any()
        );
    }

    @Test
    void mapsDuplicateRepositoryAndIdentityBindingsToConflicts() {
        activeTenant("acme", 8L);
        when(jdbcTemplate.update(
            contains("update tenant_repository"),
            eq(77L),
            eq(8L),
            eq("openai"),
            eq("repoguard")
        )).thenThrow(new DuplicateKeyException("repository duplicate"));

        assertConflict(() -> service.putRepository(
            "acme",
            new EnterpriseTenantRepositoryRequest("openai", "repoguard", 77L)
        ));

        when(jdbcTemplate.update(
            contains("update enterprise_identity"),
            eq("https://identity.example.com"),
            eq("subject-12"),
            eq(8L),
            eq(12L)
        )).thenThrow(new DuplicateKeyException("identity duplicate"));

        assertConflict(() -> service.putIdentity(
            "acme",
            new EnterpriseIdentityBindingRequest(
                12L,
                "https://identity.example.com",
                "subject-12"
            )
        ));
    }

    @Test
    void rejectsBlankOrInactiveTenantAndInactiveInitialAdmin() {
        EnterpriseTenantRepositoryRequest repository =
            new EnterpriseTenantRepositoryRequest("openai", "repoguard", 77L);

        assertBadRequest(() -> service.putRepository(" ", repository));

        when(jdbcTemplate.query(
            contains("select id from tenant"),
            org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),
            eq("missing")
        )).thenReturn(List.of());
        assertBadRequest(() -> service.putRepository("missing", repository));

        activeTenant("acme", 8L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(12L))).thenReturn(0);
        assertBadRequest(() -> service.putMembership(
            "acme",
            new EnterpriseTenantMembershipRequest(12L, "VIEWER", false)
        ));
    }

    private void activeTenant(String tenantKey, long tenantId) {
        when(jdbcTemplate.query(
            contains("select id from tenant"),
            org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),
            eq(tenantKey)
        )).thenReturn(List.of(tenantId));
    }

    private void assertConflict(Runnable operation) {
        assertThatThrownBy(operation::run)
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT)
            );
    }

    private void assertBadRequest(Runnable operation) {
        assertThatThrownBy(operation::run)
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
            );
    }
}
