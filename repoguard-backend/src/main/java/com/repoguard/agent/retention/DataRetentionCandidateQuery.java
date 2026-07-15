package com.repoguard.agent.retention;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DataRetentionCandidateQuery {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskStateMachine reviewTaskStateMachine;

    public DataRetentionCandidateQuery(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
    }

    public CandidateSelection select(LocalDateTime cutoff, int maxTasks) {
        Objects.requireNonNull(cutoff, "cutoff");
        long candidateTasks = reviewTaskMapper.selectCount(candidateTaskQuery(cutoff));
        List<Long> taskIds = reviewTaskMapper.selectList(candidateTaskQuery(cutoff)
                .orderByAsc(ReviewTask::getCreatedAt)
                .last("limit " + Math.max(1, maxTasks)))
            .stream()
            .map(ReviewTask::getId)
            .toList();
        return new CandidateSelection(candidateTasks, taskIds);
    }

    private LambdaQueryWrapper<ReviewTask> candidateTaskQuery(LocalDateTime cutoff) {
        return new LambdaQueryWrapper<ReviewTask>()
            .lt(ReviewTask::getCreatedAt, cutoff)
            .in(ReviewTask::getStatus, reviewTaskStateMachine.dataRetentionCandidateStatuses());
    }

    public record CandidateSelection(long candidateTasks, List<Long> taskIds) {

        public CandidateSelection {
            taskIds = List.copyOf(Objects.requireNonNull(taskIds, "taskIds"));
        }
    }
}
