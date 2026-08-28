package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.task.ReviewTaskMessage;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantRuntimeGuard;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReviewTaskExecutorImpl implements ReviewTaskExecutor {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewExecutionWorkflow workflow;
    private final JdbcTemplate jdbcTemplate;
    private final TenantRuntimeGuard tenantRuntimeGuard;

    @Autowired
    public ReviewTaskExecutorImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewExecutionWorkflow workflow,
        JdbcTemplate jdbcTemplate,
        TenantRuntimeGuard tenantRuntimeGuard
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.tenantRuntimeGuard = Objects.requireNonNull(tenantRuntimeGuard, "tenantRuntimeGuard");
    }

    public ReviewTaskExecutorImpl(ReviewTaskMapper reviewTaskMapper, ReviewExecutionWorkflow workflow) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.jdbcTemplate = null;
        this.tenantRuntimeGuard = null;
    }

    @Override
    public void execute(ReviewTaskMessage message) {
        Long tenantId = message.tenantId();
        if (tenantId == null && jdbcTemplate != null) {
            tenantId = jdbcTemplate.queryForObject(
                "select tenant_id from review_task where id = ?",
                Long.class,
                message.taskId()
            );
        }
        if (tenantId == null) {
            ReviewTask task = reviewTaskMapper.selectById(message.taskId());
            workflow.execute(message, task);
            return;
        }
        if (tenantRuntimeGuard != null) {
            tenantRuntimeGuard.requireActive(tenantId);
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(tenantId)) {
            ReviewTask task = reviewTaskMapper.selectById(message.taskId());
            workflow.execute(message, task);
        }
    }
}
