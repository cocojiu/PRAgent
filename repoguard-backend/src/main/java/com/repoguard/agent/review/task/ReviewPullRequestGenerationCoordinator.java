package com.repoguard.agent.review.task;

import com.repoguard.agent.mapper.ReviewPullRequestHeadMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Serializes webhook generations for one pull request and retires pending work
 * before it can spend GitHub or LLM capacity on an obsolete commit.
 */
@Component
public class ReviewPullRequestGenerationCoordinator {

    private final ReviewPullRequestHeadMapper headMapper;
    private final ReviewTaskMapper taskMapper;
    private final ReviewTimelineAppender timelineAppender;

    public ReviewPullRequestGenerationCoordinator(
        ReviewPullRequestHeadMapper headMapper,
        ReviewTaskMapper taskMapper,
        ReviewTimelineAppender timelineAppender
    ) {
        this.headMapper = Objects.requireNonNull(headMapper, "headMapper");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.timelineAppender = Objects.requireNonNull(timelineAppender, "timelineAppender");
    }

    public GenerationAdvanceResult advance(
        String organization,
        String repository,
        Integer prNumber,
        String commitSha,
        LocalDateTime occurredAt,
        LocalDateTime headUpdatedAt
    ) {
        headMapper.advance(organization, repository, prNumber, commitSha, occurredAt, headUpdatedAt);
        Long generation = headMapper.selectGeneration(organization, repository, prNumber);
        String latestCommitSha = headMapper.selectLatestCommitSha(organization, repository, prNumber);
        if (generation == null || generation < 1) {
            throw new IllegalStateException("Pull request generation could not be resolved after head update");
        }
        if (latestCommitSha == null || latestCommitSha.isBlank()) {
            throw new IllegalStateException("Pull request head could not be resolved after head update");
        }
        return new GenerationAdvanceResult(generation, commitSha.equalsIgnoreCase(latestCommitSha), latestCommitSha);
    }

    public int supersedeOlderPending(
        String organization,
        String repository,
        Integer prNumber,
        Long generation,
        String commitSha,
        LocalDateTime supersededAt
    ) {
        String reason = "Superseded by pull request generation " + generation + " at commit " + commitSha;
        int updated = taskMapper.supersedeOlderPendingTasks(
            organization,
            repository,
            prNumber,
            generation,
            supersededAt,
            reason
        );
        if (updated == 0) {
            return 0;
        }
        List<Long> supersededTaskIds = taskMapper.selectTasksSupersededAtGeneration(
            organization,
            repository,
            prNumber,
            generation,
            supersededAt
        );
        supersededTaskIds.forEach(taskId -> timelineAppender.completeCurrentAndAppend(
            taskId,
            reason,
            supersededAt,
            ReviewTimelineStatus.DONE
        ));
        return updated;
    }

    public record GenerationAdvanceResult(Long generation, boolean accepted, String latestCommitSha) {
    }
}
