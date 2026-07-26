package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.TrustedProxyClientIpResolver;
import com.repoguard.agent.common.TrustedProxyProperties;
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
import com.repoguard.agent.web.AuditClientIpResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserManagementControllerTest {

    private final UserManagementLifecycle lifecycle = Mockito.mock(UserManagementLifecycle.class);
    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new UserManagementController(lifecycle, clientIpResolver()))
        .setControllerAdvice(new com.repoguard.agent.common.GlobalExceptionHandler())
        .build();

    @Test
    void listUsersReturnsUsers() throws Exception {
        UserSearch search = new UserSearch(2, 10, "ADMIN", "ACTIVE", "adm");
        when(lifecycle.listUsers(search))
            .thenReturn(new PageResult<>(List.of(user(1001L, "admin", "ADMIN", "ACTIVE")), 21L));

        mockMvc.perform(get("/api/v1/users")
                .param("page", "2")
                .param("pageSize", "10")
                .param("role", "ADMIN")
                .param("status", "ACTIVE")
                .param("keyword", "adm"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(21))
            .andExpect(jsonPath("$.data.items[0].username").value("admin"))
            .andExpect(jsonPath("$.data.items[0].role").value("ADMIN"));

        verify(lifecycle).listUsers(search);
    }

    @Test
    void listOperationAuditsReturnsAuditItems() throws Exception {
        when(lifecycle.listOperationAudits(new PageRequest(1, 20)))
            .thenReturn(new PageResult<>(List.of(audit()), 1L));

        mockMvc.perform(get("/api/v1/users/audits"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].operatorUsername").value("admin"))
            .andExpect(jsonPath("$.data.items[0].targetUsername").value("viewer"))
            .andExpect(jsonPath("$.data.items[0].action").value("ROLE_UPDATE"));
    }

    @Test
    void createUserMapsAuthenticatedOperatorAndCommand() throws Exception {
        when(lifecycle.createUser(any(AuditContext.class), any(CreateCommand.class)))
            .thenReturn(user(1002L, "viewer", "VIEWER", "ACTIVE"));

        mockMvc.perform(post("/api/v1/users")
                .requestAttr(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, principal())
                .header("X-Forwarded-For", "10.0.0.8, 10.0.0.9")
                .header("X-Real-IP", "10.0.0.7")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.10");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "viewer",
                      "email": "viewer@repoguard.dev",
                      "password": "Secure123",
                      "confirmPassword": "Secure123"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value("viewer"))
            .andExpect(jsonPath("$.data.role").value("VIEWER"));

        ArgumentCaptor<AuditContext> contextCaptor = ArgumentCaptor.forClass(AuditContext.class);
        ArgumentCaptor<CreateCommand> commandCaptor = ArgumentCaptor.forClass(CreateCommand.class);
        verify(lifecycle).createUser(contextCaptor.capture(), commandCaptor.capture());
        assertThat(contextCaptor.getValue().operatorId()).isEqualTo(1001L);
        assertThat(contextCaptor.getValue().clientIp()).isEqualTo("192.0.2.10");
        assertThat(commandCaptor.getValue()).isEqualTo(new CreateCommand(
            "viewer",
            "viewer@repoguard.dev",
            "Secure123",
            "Secure123"
        ));
    }

    @Test
    void updateRoleMapsAuthenticatedOperatorAndCommand() throws Exception {
        when(lifecycle.updateRole(any(AuditContext.class), any(RoleChangeCommand.class)))
            .thenReturn(user(1002L, "viewer", "VIEWER", "ACTIVE"));

        mockMvc.perform(put("/api/v1/users/1002/role")
                .requestAttr(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, principal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "role": "VIEWER"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(1002))
            .andExpect(jsonPath("$.data.role").value("VIEWER"));

        ArgumentCaptor<AuditContext> contextCaptor = ArgumentCaptor.forClass(AuditContext.class);
        ArgumentCaptor<RoleChangeCommand> commandCaptor = ArgumentCaptor.forClass(RoleChangeCommand.class);
        verify(lifecycle).updateRole(contextCaptor.capture(), commandCaptor.capture());
        assertThat(contextCaptor.getValue().operatorId()).isEqualTo(1001L);
        assertThat(commandCaptor.getValue()).isEqualTo(new RoleChangeCommand(1002L, "VIEWER"));
    }

    @Test
    void updateStatusMapsAuthenticatedOperatorAndCommand() throws Exception {
        when(lifecycle.updateStatus(any(AuditContext.class), any(StatusChangeCommand.class)))
            .thenReturn(user(1002L, "viewer", "VIEWER", "DISABLED"));

        mockMvc.perform(put("/api/v1/users/1002/status")
                .requestAttr(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, principal())
                .header("X-Forwarded-For", "10.0.0.8, 10.0.0.9")
                .header("X-Real-IP", "10.0.0.7")
                .header("User-Agent", "JUnit")
                .with(request -> {
                    request.setRemoteAddr("172.18.0.2");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "status": "DISABLED"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DISABLED"));

        ArgumentCaptor<AuditContext> contextCaptor = ArgumentCaptor.forClass(AuditContext.class);
        ArgumentCaptor<StatusChangeCommand> commandCaptor = ArgumentCaptor.forClass(StatusChangeCommand.class);
        verify(lifecycle).updateStatus(contextCaptor.capture(), commandCaptor.capture());
        assertThat(contextCaptor.getValue().operatorId()).isEqualTo(1001L);
        assertThat(contextCaptor.getValue().clientIp()).isEqualTo("10.0.0.7");
        assertThat(contextCaptor.getValue().userAgent()).isEqualTo("JUnit");
        assertThat(commandCaptor.getValue()).isEqualTo(new StatusChangeCommand(1002L, "DISABLED"));
    }

    @Test
    void updateRoleWithoutAuthenticatedUserReturns401() throws Exception {
        mockMvc.perform(put("/api/v1/users/1002/role")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "role": "VIEWER"
                    }
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private AuthenticatedPrincipal principal() {
        return new AuthenticatedPrincipal(1001L, "admin", "ADMIN", 9999999999L);
    }

    private static AuditClientIpResolver clientIpResolver() {
        return new AuditClientIpResolver(
            new TrustedProxyClientIpResolver(new TrustedProxyProperties(), new SimpleMeterRegistry())
        );
    }

    private ManagedUser user(Long id, String username, String role, String status) {
        return new ManagedUser(
            id,
            username,
            username + "@repoguard.dev",
            role,
            status,
            0,
            null,
            null,
            LocalDateTime.parse("2026-06-11T10:00:00"),
            LocalDateTime.parse("2026-06-11T10:00:00")
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
            LocalDateTime.parse("2026-06-11T10:30:00")
        );
    }
}
