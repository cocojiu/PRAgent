package com.repoguard.agent.notification.workflow;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.dto.ReviewAssignmentOptionsResponse;
import com.repoguard.agent.dto.ReviewAssignmentSnapshot;
import com.repoguard.agent.dto.ReviewMemberAssignmentRequest;
import com.repoguard.agent.dto.ReviewMemberAssignmentResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.TenantMembershipMapper;
import com.repoguard.agent.service.ReviewWorkflowService;
import com.repoguard.agent.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ApiRuntimeEnabled
@EnterpriseEditionEnabled
public class ReviewMemberAssignmentService {
    private final ReviewTaskMapper tasks;
    private final TenantMembershipMapper members;
    private final ReviewWorkflowService workflow;

    public ReviewMemberAssignmentService(ReviewTaskMapper tasks, TenantMembershipMapper members, ReviewWorkflowService workflow) {
        this.tasks = tasks; this.members = members; this.workflow = workflow;
    }

    @Transactional(readOnly = true)
    public ReviewAssignmentOptionsResponse options(Long taskId, String search) {
        long tenant = tenant();
        String prefix = search == null ? "" : search.strip();
        if (prefix.length() > 128 || prefix.codePoints().anyMatch(Character::isISOControl))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid member search");
        // Escape SQL LIKE wildcards; the caller can search a literal username prefix only.
        String escaped = prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        ReviewTask task = task(taskId, tenant);
        var rows = members.selectAssignableMembers(tenant, escaped);
        return new ReviewAssignmentOptionsResponse(task.getId(), task.getCurrentAttemptId(), task.getCommitSha(),
            version(tenant, task), rows.stream().limit(20).map(row -> new ReviewAssignmentOptionsResponse.Member(row.userId(), row.username())).toList(), rows.size() > 20);
    }

    @Transactional
    public ReviewMemberAssignmentResponse confirm(Long taskId, ReviewMemberAssignmentRequest selection, String operator) {
        long tenant = tenant();
        ReviewTask task = task(taskId, tenant);
        if (selection == null || !java.util.Objects.equals(selection.attemptId(), task.getCurrentAttemptId())
            || selection.headSha() == null || !selection.headSha().equalsIgnoreCase(task.getCommitSha())
            || !version(tenant, task).equals(selection.assignmentVersion()))
            throw new BusinessException(ErrorCode.CONFLICT, "Task or assignment changed, please refresh");
        String username = members.selectAssignableUsernameByIdForUpdate(tenant, selection.userId());
        if (username == null || username.isBlank())
            throw new BusinessException(ErrorCode.CONFLICT, "Selected member is no longer eligible, please refresh");
        var result = workflow.assignRecommended(taskId, username, ReviewAssignmentSnapshot.from(task), operator);
        return new ReviewMemberAssignmentResponse(taskId, task.getCurrentAttemptId(), task.getCommitSha(), result.assignee());
    }

    private static long tenant() {
        if (!TenantContext.hasTenant() || TenantContext.currentTenantIdOrDefault() <= 0)
            throw new BusinessException(ErrorCode.FORBIDDEN, "Tenant context required");
        return TenantContext.currentTenantIdOrDefault();
    }

    private ReviewTask task(Long taskId, long tenant) {
        if (taskId == null || taskId <= 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid task");
        var task = tasks.selectOne(new QueryWrapper<ReviewTask>().eq("id", taskId).eq("tenant_id", tenant));
        if (task == null) throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found");
        if (!"PENDING_HUMAN_REVIEW".equalsIgnoreCase(task.getStatus()) || task.getCurrentAttemptId() == null
            || task.getCurrentAttemptId() <= 0 || task.getCommitSha() == null || !task.getCommitSha().matches("[a-fA-F0-9]{40}"))
            throw new BusinessException(ErrorCode.CONFLICT, "Task is not ready for assignment, please refresh");
        return task;
    }

    // An optimistic version, not an authorization token. Eligibility is checked and locked on every write.
    private static String version(long tenant, ReviewTask task) {
        var snapshot = ReviewAssignmentSnapshot.from(task);
        Object[] fields = {tenant, task.getId(), snapshot.attemptId(), snapshot.headSha(), snapshot.generation(),
            snapshot.assignee(), snapshot.assignedAt(), snapshot.deadline()};
        StringBuilder value = new StringBuilder();
        for (Object field : fields) {
            String text = field == null ? null : field.toString();
            value.append(text == null ? "-1:" : text.length() + ":" + text);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
