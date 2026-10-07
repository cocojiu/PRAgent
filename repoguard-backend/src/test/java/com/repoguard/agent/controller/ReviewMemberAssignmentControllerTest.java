package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.GlobalExceptionHandler;
import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.dto.*;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.TenantMembershipMapper;
import com.repoguard.agent.mapper.projection.AssignableReviewMember;
import com.repoguard.agent.notification.workflow.ReviewMemberAssignmentService;
import com.repoguard.agent.security.RoleAuthorizationInterceptor;
import com.repoguard.agent.service.ReviewWorkflowService;
import com.repoguard.agent.tenancy.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SuppressWarnings({"try", "unchecked"})
class ReviewMemberAssignmentControllerTest {
    private final ReviewTaskMapper tasks = mock(ReviewTaskMapper.class);
    private final TenantMembershipMapper members = mock(TenantMembershipMapper.class);
    private final ReviewWorkflowService workflow = mock(ReviewWorkflowService.class);
    private final ReviewMemberAssignmentService service = new ReviewMemberAssignmentService(tasks, members, workflow);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new ReviewMemberAssignmentController(service)).setControllerAdvice(new GlobalExceptionHandler())
        .addInterceptors(new RoleAuthorizationInterceptor(new JacksonConfig().objectMapper())).build();
    private final String url = "/api/v1/review-workflow/tasks/9/assignment-options";
    private final String confirmUrl = "/api/v1/review-workflow/tasks/9/assignment/confirm";
    @BeforeEach void setup() {
        var task = new ReviewTask(); task.setId(9L); task.setCurrentAttemptId(12L); task.setCommitSha("a".repeat(40));
        task.setGeneration(2L); task.setStatus("PENDING_HUMAN_REVIEW"); when(tasks.selectOne(any(QueryWrapper.class))).thenReturn(task);
        when(members.selectAssignableMembers(anyLong(), anyString())).thenReturn(List.of(new AssignableReviewMember(11L, "reviewer")));
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String role) {
        return request.requestAttr(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, new AuthenticatedPrincipal(7L, "operator", role, Long.MAX_VALUE));
    }
    @Test void anonymousOrNonReviewRolesCannotEnumerateOrAssignMembers() throws Exception {
        mvc.perform(get(url)).andExpect(status().isUnauthorized()); mvc.perform(post(confirmUrl)).andExpect(status().isUnauthorized());
        for (String role : List.of("READ_ONLY", "VIEWER", "RULE_ADMIN")) {
            mvc.perform(auth(get(url), role)).andExpect(status().isForbidden());
            mvc.perform(auth(post(confirmUrl), role)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(tasks, members, workflow);
    }
    @Test void missingTenantDoesNotReachSqlEvenForAdmin() throws Exception {
        mvc.perform(auth(get(url), "ADMIN")).andExpect(status().isForbidden()); verifyNoInteractions(tasks, members, workflow);
    }
    @Test void qualifiedRolesReadBoundedNoStoreOptionsWithoutPrivateAccountData() throws Exception {
        try (var scope = TenantContext.withTenant(7L)) {
            for (String role : List.of("ADMIN", "PLATFORM_ADMIN", "TENANT_ADMIN", "REVIEWER")) {
                var response = mvc.perform(auth(get(url).param("search", "rev"), role)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.members[0].userId").value(11))
                    .andExpect(jsonPath("$.data.members[0].username").value("reviewer")).andReturn();
                assertThat(response.getResponse().getContentAsString()).doesNotContain("email", "password", "token");
            }
        }
    }
    @Test void validConfirmationUsesCurrentServerStateAndCanonicalMemberIdentity() throws Exception {
        when(members.selectAssignableUsernameByIdForUpdate(7L, 11L)).thenReturn("reviewer");
        when(workflow.assignRecommended(eq(9L), eq("reviewer"), any(), eq("operator"))).thenReturn(
            new ReviewWorkflowItemDto(9L, "owner/repo", 3, "title", "PENDING_HUMAN_REVIEW", "PENDING", "reviewer", "now", "later", 0, false));
        try (var scope = TenantContext.withTenant(7L)) {
            var options = service.options(9L, null);
            var body = new ReviewMemberAssignmentRequest(11L, 12L, "a".repeat(40), options.assignmentVersion());
            mvc.perform(auth(post(confirmUrl).contentType("application/json").content(new JacksonConfig().objectMapper().writeValueAsString(body)), "REVIEWER"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.assignee").value("reviewer")).andExpect(jsonPath("$.data.attemptId").value(12));
        }
    }
    @Test void invalidBodyAndOversizedSearchAreRejectedBeforeSql() throws Exception {
        try (var scope = TenantContext.withTenant(7L)) {
            for (String body : List.of("{}", "{\"userId\":0,\"attemptId\":12,\"headSha\":\"short\",\"assignmentVersion\":\"bad\"}"))
                mvc.perform(auth(post(confirmUrl).contentType("application/json").content(body), "REVIEWER")).andExpect(status().isBadRequest());
            mvc.perform(auth(get(url).param("search", "x".repeat(129)), "REVIEWER")).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(tasks, members, workflow);
    }
    @Test void staleVersionOrRevokedMemberCannotReturnSuccess() throws Exception {
        try (var scope = TenantContext.withTenant(7L)) {
            var options = service.options(9L, null);
            for (String version : List.of("c".repeat(64), options.assignmentVersion())) {
                var body = new ReviewMemberAssignmentRequest(11L, 12L, "a".repeat(40), version);
                mvc.perform(auth(post(confirmUrl).contentType("application/json").content(new JacksonConfig().objectMapper().writeValueAsString(body)), "REVIEWER"))
                    .andExpect(status().isConflict());
            }
        }
        verifyNoInteractions(workflow);
    }
}
