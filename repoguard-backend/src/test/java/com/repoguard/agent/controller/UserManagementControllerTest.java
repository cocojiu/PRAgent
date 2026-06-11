package com.repoguard.agent.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.security.AuthTokenFilter;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.service.UserManagementService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
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
    void updateRolePassesAuthenticatedOperator() throws Exception {
        Mockito.when(userManagementService.updateRole(1001L, 1002L, "VIEWER"))
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
    }

    @Test
    void updateStatusPassesAuthenticatedOperator() throws Exception {
        Mockito.when(userManagementService.updateStatus(1001L, 1002L, "DISABLED"))
            .thenReturn(item(1002L, "viewer", "VIEWER", "DISABLED"));

        mockMvc.perform(put("/api/v1/users/1002/status")
                .requestAttr(
                    AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE,
                    new AuthTokenService.AuthenticatedUser(1001L, "admin", "ADMIN", 9999999999L)
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "status": "DISABLED"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DISABLED"));
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
}
