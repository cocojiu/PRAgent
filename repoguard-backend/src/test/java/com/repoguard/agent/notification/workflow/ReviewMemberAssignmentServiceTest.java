package com.repoguard.agent.notification.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.*;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.TenantMembershipMapper;
import com.repoguard.agent.mapper.projection.AssignableReviewMember;
import com.repoguard.agent.service.ReviewWorkflowService;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

@SuppressWarnings({"try", "unchecked"})
class ReviewMemberAssignmentServiceTest {
    private final ReviewTaskMapper tasks = mock(ReviewTaskMapper.class);
    private final TenantMembershipMapper members = mock(TenantMembershipMapper.class);
    private final ReviewWorkflowService workflow = mock(ReviewWorkflowService.class);
    private final ReviewMemberAssignmentService service = new ReviewMemberAssignmentService(tasks, members, workflow);
    private ReviewTask task;
    @BeforeEach void setup() {
        task = new ReviewTask(); task.setId(9L); task.setCurrentAttemptId(12L); task.setCommitSha("a".repeat(40));
        task.setGeneration(2L); task.setStatus("PENDING_HUMAN_REVIEW");
        when(tasks.selectOne(any(QueryWrapper.class))).thenReturn(task);
        when(members.selectAssignableMembers(anyLong(), anyString())).thenReturn(List.of(new AssignableReviewMember(11L, "reviewer")));
    }
    private ReviewMemberAssignmentRequest selection() {
        var options = service.options(9L, null);
        return new ReviewMemberAssignmentRequest(11L, options.attemptId(), options.headSha(), options.assignmentVersion());
    }
    @Test void rejectsMissingTenantBeforeAnyDatabaseAccess() {
        assertThatThrownBy(() -> service.options(9L, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.confirm(9L, null, "operator")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(tasks, members, workflow);
    }
    @Test void bindsTheTaskAndMemberListToCurrentTenantAndBoundsTheResult() {
        when(members.selectAssignableMembers(7L, "")).thenReturn(LongStream.rangeClosed(1, 21)
            .mapToObj(id -> new AssignableReviewMember(id, "member" + id)).toList());
        try (var scope = TenantContext.withTenant(7L)) {
            var result = service.options(9L, null);
            assertThat(result.members()).hasSize(20); assertThat(result.hasMore()).isTrue();
            assertThat(result.assignmentVersion()).matches("[a-f0-9]{64}");
        }
        var query = ArgumentCaptor.forClass(QueryWrapper.class); verify(tasks).selectOne(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("id =", "tenant_id =");
        assertThat(query.getValue().getParamNameValuePairs().values()).containsExactlyInAnyOrder(9L, 7L);
        verify(members).selectAssignableMembers(7L, "");
    }
    @Test void searchesLiteralUsernamePrefixWithoutWildcardExpansion() {
        try (var scope = TenantContext.withTenant(7L)) { service.options(9L, "  a!%_  "); }
        verify(members).selectAssignableMembers(7L, "a!!!%!_");
    }
    @Test void rejectsOversizedOrControlCharacterSearchBeforeMemberQuery() {
        try (var scope = TenantContext.withTenant(7L)) {
            assertThatThrownBy(() -> service.options(9L, "x".repeat(129))).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.options(9L, "a\nb")).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(members);
    }
    @Test void rejectsMissingOrIneligibleTaskWithoutEnumeratingMembers() {
        try (var scope = TenantContext.withTenant(7L)) {
            when(tasks.selectOne(any(QueryWrapper.class))).thenReturn(null);
            assertThatThrownBy(() -> service.options(9L, null)).isInstanceOf(BusinessException.class);
            when(tasks.selectOne(any(QueryWrapper.class))).thenReturn(task); task.setStatus("COMPLETED");
            assertThatThrownBy(() -> service.options(9L, null)).isInstanceOf(BusinessException.class);
            task.setStatus("PENDING_HUMAN_REVIEW"); task.setCommitSha("short");
            assertThatThrownBy(() -> service.options(9L, null)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(members, workflow);
    }
    @Test void confirmsFreshActiveMemberByIdAndPassesTheServerSnapshot() {
        when(members.selectAssignableUsernameByIdForUpdate(7L, 11L)).thenReturn("canonical-reviewer");
        when(workflow.assignRecommended(eq(9L), eq("canonical-reviewer"), any(), eq("operator"))).thenReturn(
            new ReviewWorkflowItemDto(9L, "owner/repo", 3, "title", "PENDING_HUMAN_REVIEW", "PENDING", "canonical-reviewer", "now", "later", 0, false));
        try (var scope = TenantContext.withTenant(7L)) {
            var result = service.confirm(9L, selection(), "operator");
            assertThat(result.assignee()).isEqualTo("canonical-reviewer"); assertThat(result.attemptId()).isEqualTo(12L);
        }
        var snapshot = ArgumentCaptor.forClass(ReviewAssignmentSnapshot.class);
        verify(workflow).assignRecommended(eq(9L), eq("canonical-reviewer"), snapshot.capture(), eq("operator"));
        assertThat(snapshot.getValue()).isEqualTo(ReviewAssignmentSnapshot.from(task));
    }
    @Test void revokedOrForeignMemberCannotReachTheAssignmentWrite() {
        try (var scope = TenantContext.withTenant(7L)) {
            var request = selection();
            assertThatThrownBy(() -> service.confirm(9L, request, "operator")).isInstanceOf(BusinessException.class);
        }
        verify(members).selectAssignableUsernameByIdForUpdate(7L, 11L); verifyNoInteractions(workflow);
    }
    @ParameterizedTest @ValueSource(strings = {"attempt", "head", "generation", "assignee", "assignedAt", "deadline", "tenant"})
    void rejectsEachChangedSnapshotFieldBeforeLookingUpTheMember(String change) {
        try (var scope = TenantContext.withTenant(7L)) {
            var request = selection();
            switch (change) {
                case "attempt" -> task.setCurrentAttemptId(13L);
                case "head" -> task.setCommitSha("b".repeat(40));
                case "generation" -> task.setGeneration(3L);
                case "assignee" -> task.setReviewAssignee("another-reviewer");
                case "assignedAt" -> task.setReviewAssignedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
                case "deadline" -> task.setReviewSlaDeadline(LocalDateTime.of(2026, 1, 2, 0, 0));
                default -> { }
            }
            if (change.equals("tenant")) {
                try (var other = TenantContext.withTenant(8L)) {
                    assertThatThrownBy(() -> service.confirm(9L, request, "operator")).isInstanceOf(BusinessException.class);
                }
            } else assertThatThrownBy(() -> service.confirm(9L, request, "operator")).isInstanceOf(BusinessException.class);
        }
        verify(members, never()).selectAssignableUsernameByIdForUpdate(anyLong(), any()); verifyNoInteractions(workflow);
    }
    @Test void preservesTheWorkflowConflictIfAConcurrentWriteWinsAfterTheRead() {
        when(members.selectAssignableUsernameByIdForUpdate(7L, 11L)).thenReturn("reviewer");
        when(workflow.assignRecommended(any(), any(), any(), any())).thenThrow(new BusinessException(ErrorCode.CONFLICT, "changed"));
        try (var scope = TenantContext.withTenant(7L)) {
            var request = selection(); assertThatThrownBy(() -> service.confirm(9L, request, "operator"))
                .isInstanceOf(BusinessException.class).hasMessage("changed");
        }
    }
}
