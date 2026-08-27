package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.task.ReviewTaskMessage;
import com.repoguard.agent.tenancy.TenantContext;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReviewTaskExecutorImpl implements ReviewTaskExecutor {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewExecutionWorkflow workflow;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ReviewTaskExecutorImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewExecutionWorkflow workflow,
        JdbcTemplate jdbcTemplate
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    public ReviewTaskExecutorImpl(ReviewTaskMapper reviewTaskMapper, ReviewExecutionWorkflow workflow) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.jdbcTemplate = null;
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
        try (TenantContext.Scope _ = TenantContext.withTenant(tenantId)) {
            ReviewTask task = reviewTaskMapper.selectById(message.taskId());
            workflow.execute(message, task);
        }
    }
}
