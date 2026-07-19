package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.UserCreateRequest;
import com.repoguard.agent.dto.UserOperationAuditContext;
import com.repoguard.agent.user.UserManagementLifecycle;
import com.repoguard.agent.user.UserManagementLifecycle.AuditContext;
import com.repoguard.agent.user.UserManagementLifecycle.CreateCommand;
import com.repoguard.agent.user.UserManagementLifecycle.ManagedUser;
import com.repoguard.agent.user.UserManagementLifecycle.OperationAudit;
import com.repoguard.agent.user.UserManagementLifecycle.PageRequest;
import com.repoguard.agent.user.UserManagementLifecycle.PageResult;
import com.repoguard.agent.user.UserManagementLifecycle.RoleChangeCommand;
import com.repoguard.agent.user.UserManagementLifecycle.StatusChangeCommand;
import com.repoguard.agent.user.UserManagementLifecycle.UserSearch;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UserManagementServiceImplTest {

    private final UserManagementLifecycle lifecycle = Mockito.mock(UserManagementLifecycle.class);
    private final UserManagementServiceImpl service = new UserManagementServiceImpl(lifecycle);

    @Test
    void listUsersMapsSearchAndUserViews() {
        UserSearch search = new UserSearch(2, 10, "ADMIN", "ACTIVE", "adm");
        when(lifecycle.listUsers(search)).thenReturn(new PageResult<>(List.of(user()), 21L));

        var result = service.listUsers(2, 10, "ADMIN", "ACTIVE", "adm");

        assertThat(result.total()).isEqualTo(21L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).username()).isEqualTo("admin");
        assertThat(result.items().get(0).role()).isEqualTo("ADMIN");
        verify(lifecycle).listUsers(search);
    }

    @Test
    void listOperationAuditsMapsPageAndAuditViews() {
        PageRequest pageRequest = new PageRequest(1, 20);
        when(lifecycle.listOperationAudits(pageRequest))
            .thenReturn(new PageResult<>(List.of(audit()), 1L));

        var result = service.listOperationAudits(1, 20);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items().get(0).operatorUsername()).isEqualTo("admin");
        assertThat(result.items().get(0).targetUsername()).isEqualTo("viewer");
        verify(lifecycle).listOperationAudits(pageRequest);
    }

    @Test
    void createUserMapsAuditContextAndCommand() {
        AuditContext auditContext = new AuditContext(1001L, "10.0.0.1", "JUnit");
        CreateCommand command = new CreateCommand(
            "admin",
            "admin@repoguard.dev",
            "Secure123",
            "Secure123"
        );
        when(lifecycle.createUser(auditContext, command)).thenReturn(user());

        var result = service.createUser(
            new UserOperationAuditContext(1001L, "10.0.0.1", "JUnit"),
            new UserCreateRequest(
                "admin",
                "admin@repoguard.dev",
                "Secure123",
                "Secure123"
            )
        );

        assertThat(result.username()).isEqualTo("admin");
        verify(lifecycle).createUser(auditContext, command);
    }

    @Test
    void updateRoleMapsCommandAndResult() {
        AuditContext auditContext = new AuditContext(1001L, "10.0.0.1", "JUnit");
        RoleChangeCommand command = new RoleChangeCommand(1002L, "ADMIN");
        when(lifecycle.updateRole(auditContext, command)).thenReturn(user());

        var result = service.updateRole(
            new UserOperationAuditContext(1001L, "10.0.0.1", "JUnit"),
            1002L,
            "ADMIN"
        );

        assertThat(result.role()).isEqualTo("ADMIN");
        verify(lifecycle).updateRole(auditContext, command);
    }

    @Test
    void updateStatusPreservesMissingAuditContext() {
        StatusChangeCommand command = new StatusChangeCommand(1002L, "ACTIVE");
        when(lifecycle.updateStatus(null, command)).thenReturn(user());

        var result = service.updateStatus(null, 1002L, "ACTIVE");

        assertThat(result.status()).isEqualTo("ACTIVE");
        verify(lifecycle).updateStatus(null, command);
    }

    @Test
    void constructorRejectsMissingLifecycle() {
        assertThatThrownBy(() -> new UserManagementServiceImpl(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("lifecycle");
    }

    private ManagedUser user() {
        LocalDateTime now = LocalDateTime.parse("2026-07-19T14:00:00");
        return new ManagedUser(
            1001L,
            "admin",
            "admin@repoguard.dev",
            "ADMIN",
            "ACTIVE",
            0,
            null,
            null,
            now,
            now
        );
    }

    private OperationAudit audit() {
        return new OperationAudit(
            9001L,
            1001L,
            "admin",
            1002L,
            "viewer",
            "ROLE_UPDATE",
            "ADMIN",
            "VIEWER",
            "10.0.0.1",
            "JUnit",
            LocalDateTime.parse("2026-07-19T14:10:00")
        );
    }
}
