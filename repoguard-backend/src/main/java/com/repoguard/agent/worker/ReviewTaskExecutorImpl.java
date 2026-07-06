package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReviewTaskExecutorImpl implements ReviewTaskExecutor {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewExecutionWorkflow workflow;

    @Autowired
    public ReviewTaskExecutorImpl(ReviewTaskMapper reviewTaskMapper, ReviewExecutionWorkflow workflow) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
    }

    @Override
    public void execute(ReviewTaskMessage message) {
        ReviewTask task = reviewTaskMapper.selectById(message.taskId());
        workflow.execute(message, task);
    }
}
