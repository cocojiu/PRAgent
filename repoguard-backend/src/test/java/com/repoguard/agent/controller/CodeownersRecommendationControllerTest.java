package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.GlobalExceptionHandler;
import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.notification.workflow.CodeownersQueryService;
import com.repoguard.agent.security.RoleAuthorizationInterceptor;
import com.repoguard.agent.tenancy.TenantContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SuppressWarnings("try")
class CodeownersRecommendationControllerTest {
    private final CodeownersQueryService query = mock(CodeownersQueryService.class);
    private final com.repoguard.agent.service.ReviewWorkflowService workflow = mock(com.repoguard.agent.service.ReviewWorkflowService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new CodeownersRecommendationController(query, workflow)).setControllerAdvice(new GlobalExceptionHandler())
        .addInterceptors(new RoleAuthorizationInterceptor(new JacksonConfig().objectMapper())).build();
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String role) {
        return get("/api/v1/review-workflow/tasks/9/codeowners").requestAttr(
            RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, new AuthenticatedPrincipal(7L, "fixture", role, Long.MAX_VALUE));
    }
    @Test void rejectsAnonymousAndNonReviewRolesBeforeReading() throws Exception {
        mvc.perform(get("/api/v1/review-workflow/tasks/9/codeowners")).andExpect(status().isUnauthorized());
        for (String role : List.of("READ_ONLY", "VIEWER", "RULE_ADMIN")) mvc.perform(request(role)).andExpect(status().isForbidden());
        verifyNoInteractions(query);
    }
    @Test void requiresTenantEvenForAdministrator() throws Exception {
        mvc.perform(request("ADMIN")).andExpect(status().isForbidden());
        verifyNoInteractions(query);
    }
    @Test void authorizedRolesReceiveNoStoreAndExplicitDisabledStatus() throws Exception {
        when(query.query(9)).thenReturn(new CodeownersQueryService.Response("DISABLED", 9L, null, null, null, null, null));
        try (var ignored = TenantContext.withTenant(7L)) {
            for (String role : List.of("ADMIN", "PLATFORM_ADMIN", "TENANT_ADMIN", "REVIEWER")) {
                mvc.perform(request(role)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.status").value("DISABLED")).andExpect(jsonPath("$.data.candidates").isEmpty());
            }
        }
    }
    @Test void absentTaskUses404() throws Exception {
        when(query.query(9)).thenReturn(new CodeownersQueryService.Response("NOT_FOUND", 9L, null, null, null, null, null));
        try (var ignored = TenantContext.withTenant(7L)) { mvc.perform(request("REVIEWER")).andExpect(status().isNotFound()); }
    }
    @Test void projectsOnlyRecommendationEvidence() throws Exception {
        var recommendation = com.repoguard.agent.review.codeowners.CodeownersRecommendations.recommend("* @a",
            List.of(new com.repoguard.agent.review.codeowners.CodeownersRecommendations.ChangedPath("A.java", "HIGH", 1)),
            java.util.Map.of("@a", List.of(11L)), java.util.Map.of(11L, "reviewer"));
        when(query.query(9)).thenReturn(new CodeownersQueryService.Response("RECOMMENDATIONS", 9L, 12L, "head", "base", "CODEOWNERS", recommendation));
        try (var ignored = TenantContext.withTenant(7L)) {
            var response = mvc.perform(request("REVIEWER")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].userId").value(11))
                .andExpect(jsonPath("$.data.basis[0].path").value("A.java")).andReturn();
            assertThat(response.getResponse().getContentAsString()).doesNotContain("mappingFile", "content", "token");
        }
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder acceptRequest(String role, long user,
                                                                                                  long attempt, String head, String base) throws Exception {
        return post("/api/v1/review-workflow/tasks/9/codeowners/accept").contentType("application/json")
            .content(new JacksonConfig().objectMapper().writeValueAsString(new com.repoguard.agent.dto.CodeownersAcceptRequest(user, attempt, head, base)))
            .requestAttr(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, new AuthenticatedPrincipal(7L, "fixture", role, Long.MAX_VALUE));
    }
    private CodeownersQueryService.Response recommended() {
        var task = new com.repoguard.agent.entity.ReviewTask(); task.setCurrentAttemptId(12L); task.setCommitSha("a".repeat(40)); task.setGeneration(2L);
        var recommendation = com.repoguard.agent.review.codeowners.CodeownersRecommendations.recommend("* @a",
            List.of(new com.repoguard.agent.review.codeowners.CodeownersRecommendations.ChangedPath("A.java", "HIGH", 1)),
            java.util.Map.of("@a", List.of(11L)), java.util.Map.of(11L, "reviewer"));
        return new CodeownersQueryService.Response("RECOMMENDATIONS", 9L, 12L, task.getCommitSha(), "b".repeat(40), "CODEOWNERS", recommendation,
            com.repoguard.agent.dto.ReviewAssignmentSnapshot.from(task));
    }
    @Test void acceptsOnlyFreshServerRecommendedUserAndCarriesServerSnapshot() throws Exception {
        var recommendation = recommended(); when(query.query(9)).thenReturn(recommendation);
        when(workflow.assignRecommended(9L, "reviewer", recommendation.assignment(), "fixture")).thenReturn(
            new com.repoguard.agent.dto.ReviewWorkflowItemDto(9L, "owner/repo", 3, "title", "PENDING_HUMAN_REVIEW", "PENDING",
                "reviewer", "now", "later", 0, false));
        try (var scope = TenantContext.withTenant(7L)) {
            mvc.perform(acceptRequest("REVIEWER", 11, 12, "a".repeat(40), "b".repeat(40)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.assignee").value("reviewer")).andExpect(jsonPath("$.data.attemptId").value(12));
        }
        verify(workflow).assignRecommended(9L, "reviewer", recommendation.assignment(), "fixture");
    }
    @Test void rejectsChangedVersionAndArbitraryUserBeforeAssignment() throws Exception {
        when(query.query(9)).thenReturn(recommended());
        try (var scope = TenantContext.withTenant(7L)) {
            mvc.perform(acceptRequest("REVIEWER", 99, 12, "a".repeat(40), "b".repeat(40))).andExpect(status().isConflict());
            mvc.perform(acceptRequest("REVIEWER", 11, 13, "a".repeat(40), "b".repeat(40))).andExpect(status().isConflict());
            mvc.perform(acceptRequest("REVIEWER", 11, 12, "c".repeat(40), "b".repeat(40))).andExpect(status().isConflict());
            mvc.perform(acceptRequest("REVIEWER", 11, 12, "a".repeat(40), "c".repeat(40))).andExpect(status().isConflict());
            when(query.query(9)).thenReturn(new CodeownersQueryService.Response("DISABLED", 9L, null, null, null, null, null));
            mvc.perform(acceptRequest("REVIEWER", 11, 12, "a".repeat(40), "b".repeat(40))).andExpect(status().isConflict());
        }
        verifyNoInteractions(workflow);
    }
    @Test void acceptanceRequiresAuthenticationTenantReviewRoleAndValidBody() throws Exception {
        mvc.perform(post("/api/v1/review-workflow/tasks/9/codeowners/accept")).andExpect(status().isUnauthorized());
        mvc.perform(acceptRequest("ADMIN", 11, 12, "a".repeat(40), "b".repeat(40))).andExpect(status().isForbidden());
        try (var scope = TenantContext.withTenant(7L)) {
            mvc.perform(acceptRequest("READ_ONLY", 11, 12, "a".repeat(40), "b".repeat(40))).andExpect(status().isForbidden());
            mvc.perform(acceptRequest("REVIEWER", 0, 12, "a".repeat(40), "b".repeat(40))).andExpect(status().isBadRequest());
            mvc.perform(acceptRequest("REVIEWER", 11, 12, "short", "b".repeat(40))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(query, workflow);
    }
}
