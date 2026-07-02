package com.repoguard.agent.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.dto.UserOperationAuditContext;
import com.repoguard.agent.dto.UserOperationAuditDto;
import com.repoguard.agent.security.AuthTokenFilter;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.service.UserManagementService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserManagementControllerTest {

    private final UserManagementService userManagementService = Mockito.mock(UserManagementService.class);
    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new UserManagementController(userManagementService))
        .setControllerAdvice(new com.repoguard.agent.common.GlobalExceptionHandler())
        .build();

    @Test
    void listUsersReturnsUsers() throws Exception {
        Mockito.when(userManagementService.listUsers()).thenReturn(List.of(item(1001L, "admin", "ADMIN", "ACTIVE")));

        mockMvc.perform(get("/api/v1/users"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].username").value("admin"))
            .andExpect(jsonPath("$.data[0].role").value("ADMIN"));
    }

    @Test
    void listOperationAuditsReturnsAuditItems() throws Exception {
        Mockito.when(userManagementService.listOperationAudits()).thenReturn(List.of(audit()));

        mockMvc.perform(get("/api/v1/users/audits"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].operatorUsername").value("admin"))
            .andExpect(jsonPath("$.data[0].targetUsername").value("viewer"))
            .andExpect(jsonPath("$.data[0].action").value("ROLE_UPDATE"));
    }

    @Test
    void createUserPassesAuthenticatedOperator() throws Exception {
        Mockito.when(userManagementService.createUser(ArgumentMatchers.any(UserOperationAuditContext.class), ArgumentMatchers.any()))
            .thenReturn(item(1002L, "viewer", "VIEWER", "ACTIVE"));

        mockMvc.perform(post("/api/v1/users")
                .requestAttr(
                    AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE,
                    new AuthTokenService.AuthenticatedUser(1001L, "admin", "ADMIN", 9999999999L)
                )
                .header("X-Forwarded-For", "10.0.0.8, 10.0.0.9")
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

        ArgumentCaptor<UserOperationAuditContext> contextCaptor = ArgumentCaptor.forClass(UserOperationAuditContext.class);
        Mockito.verify(userManagementService).createUser(contextCaptor.capture(), ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThat(contextCaptor.getValue().operatorId()).isEqualTo(1001L);
        org.assertj.core.api.Assertions.assertThat(contextCaptor.getValue().clientIp()).isEqualTo("10.0.0.8");
    }

    @Test
    void updateRolePassesAuthenticatedOperator() throws Exception {
        Mockito.when(userManagementService.updateRole(ArgumentMatchers.any(UserOperationAuditContext.class), ArgumentMatchers.eq(1002L), ArgumentMatchers.eq("VIEWER")))
            .thenReturn(item(1002L, "viewer", "VIEWER", "ACTIVE"));

        mockMvc.perform(put("/api/v1/users/1002/role")
                .requestAttr(
                    AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE,
                    new AuthTokenService.AuthenticatedUser(1001L, "admin", "ADMIN", 9999999999L)
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "role": "VIEWER"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(1002))
            .andExpect(jsonPath("$.data.role").value("VIEWER"));

        ArgumentCaptor<UserOperationAuditContext> contextCaptor = ArgumentCaptor.forClass(UserOperationAuditContext.class);
        Mockito.verify(userManagementService).updateRole(contextCaptor.capture(), ArgumentMatchers.eq(1002L), ArgumentMatchers.eq("VIEWER"));
        org.assertj.core.api.Assertions.assertThat(contextCaptor.getValue().operatorId()).isEqualTo(1001L);
    }

    @Test
    void updateStatusPassesAuthenticatedOperator() throws Exception {
        Mockito.when(userManagementService.updateStatus(ArgumentMatchers.any(UserOperationAuditContext.class), ArgumentMatchers.eq(1002L), ArgumentMatchers.eq("DISABLED")))
            .thenReturn(item(1002L, "viewer", "VIEWER", "DISABLED"));

        mockMvc.perform(put("/api/v1/users/1002/status")
                .requestAttr(
                    AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE,
                    new AuthTokenService.AuthenticatedUser(1001L, "admin", "ADMIN", 9999999999L)
                )
                .header("X-Forwarded-For", "10.0.0.8, 10.0.0.9")
                .header("User-Agent", "JUnit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "status": "DISABLED"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DISABLED"));

        ArgumentCaptor<UserOperationAuditContext> contextCaptor = ArgumentCaptor.forClass(UserOperationAuditContext.class);
        Mockito.verify(userManagementService).updateStatus(contextCaptor.capture(), ArgumentMatchers.eq(1002L), ArgumentMatchers.eq("DISABLED"));
        org.assertj.core.api.Assertions.assertThat(contextCaptor.getValue().operatorId()).isEqualTo(1001L);
        org.assertj.core.api.Assertions.assertThat(contextCaptor.getValue().clientIp()).isEqualTo("10.0.0.8");
        org.assertj.core.api.Assertions.assertThat(contextCaptor.getValue().userAgent()).isEqualTo("JUnit");
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

    private UserManagementItemDto item(Long id, String username, String role, String status) {
        return new UserManagementItemDto(
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

    private UserOperationAuditDto audit() {
        return new UserOperationAuditDto(
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
