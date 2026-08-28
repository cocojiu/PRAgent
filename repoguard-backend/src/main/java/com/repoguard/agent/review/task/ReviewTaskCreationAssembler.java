package com.repoguard.agent.review.task;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.review.AssessmentStatus;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskSource;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ReviewTaskCreationAssembler {

    private final ReviewTaskStateMachine stateMachine;

    ReviewTaskCreationAssembler(ReviewTaskStateMachine stateMachine) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "reviewTaskStateMachine");
    }

    CreationCommand command(ManualReviewRequest request, LocalDateTime createdAt) {
        Objects.requireNonNull(request, "request");
        String organization = request.organization().trim();
        String repository = request.repository().trim();
        String commit = resolveCommit(request.commit());
        ReviewTaskSource source = ReviewTaskSource.creationSource(request.source());
        return new CreationCommand(
            organization,
            repository,
            request.prNumber(),
            resolveTitle(request.title(), request.prNumber()),
            commit,
            resolveBranch(request.branch()),
            source,
            buildPrUrl(organization, repository, request.prNumber()),
            Objects.requireNonNull(createdAt, "createdAt")
        );
    }

    ReviewTask task(CreationCommand command) {
        ReviewTask task = new ReviewTask();
        task.setPrNumber(command.prNumber());
        task.setTitle(command.title());
        task.setRepository(command.repository());
        task.setOrganization(command.organization());
        task.setCommitSha(command.commit());
        task.setGeneration(1L);
        task.setBranchName(command.branch());
        task.setStatus(stateMachine.statusWhenQueued());
        task.setRiskLevel("INFO");
        task.setAssessmentStatus(AssessmentStatus.PARTIAL.name());
        task.setMqRetries(0);
        task.setPublishAttempts(0);
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPrUrl(command.prUrl());
        task.setSource(command.source().code());
        task.setTriggerSource(command.source().code());
        task.setHumanReviewRequired(false);
        task.setHumanReviewStatus(HumanReviewStatus.NOT_REQUIRED.code());
        task.setCreatedAt(command.createdAt());
        task.setDurationSeconds(0);
        return task;
    }

    void markStaleWebhook(ReviewTask task, LocalDateTime createdAt, String latestCommitSha) {
        task.setStatus(stateMachine.statusWhenSuperseded());
        task.setRiskLevel("INFO");
        task.setAssessmentStatus(AssessmentStatus.SUPERSEDED.name());
        task.setFinishedAt(createdAt);
        task.setDurationSeconds(0);
        task.setLlmFallbackReason("Stale webhook ignored; current pull request head is " + latestCommitSha);
    }

    ReviewTaskMessage message(ReviewTask task, CreationCommand command) {
        return new ReviewTaskMessage(
            task.getId(),
            command.organization(),
            command.repository(),
            command.prNumber(),
            command.commit(),
            command.createdAt(),
            LogContext.currentTraceId(),
            command.source() == ReviewTaskSource.GITHUB_WEBHOOK ? 4 : 8
        );
    }

    ManualReviewResponse reusedResponse(ReviewTask task) {
        return new ManualReviewResponse(
            task.getId(),
            lower(task.getStatus()),
            "Review task already exists",
            true,
            ReviewTaskSource.dtoCodeOrDefault(task.getSource()),
            ReviewTaskSource.EXISTING_REUSED.dtoCode()
        );
    }

    ManualReviewResponse staleWebhookResponse(ReviewTask task, ReviewTaskSource source) {
        return new ManualReviewResponse(
            task.getId(),
            "superseded",
            "Stale webhook ignored because a newer pull request head is already known",
            false,
            source.dtoCode(),
            source.dtoCode()
        );
    }

    ManualReviewResponse publishResponse(ReviewTask task, ReviewTaskSource source, boolean queued) {
        return new ManualReviewResponse(
            task.getId(),
            queued ? "queued" : "publish_failed",
            queued ? "Review task queued" : "Review task saved, waiting for message publish compensation",
            false,
            source.dtoCode(),
            source.dtoCode()
        );
    }

    private String resolveCommit(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Commit SHA is required");
        }
        String commit = value.trim();
        if (!commit.matches("(?i)^[0-9a-f]{40}([0-9a-f]{24})?$")) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Commit SHA must be a 40 or 64 character hexadecimal value"
            );
        }
        return commit.toLowerCase(Locale.ROOT);
    }

    private String resolveTitle(String title, Integer prNumber) {
        return StringUtils.hasText(title) ? title.trim() : "Manual review for PR #" + prNumber;
    }

    private String resolveBranch(String branch) {
        return StringUtils.hasText(branch) ? branch.trim() : "unknown";
    }

    private String buildPrUrl(String organization, String repository, Integer prNumber) {
        return "https://github.com/" + organization + "/" + repository + "/pull/" + prNumber;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    record CreationCommand(
        String organization,
        String repository,
        Integer prNumber,
        String title,
        String commit,
        String branch,
        ReviewTaskSource source,
        String prUrl,
        LocalDateTime createdAt
    ) {
        String idempotencyKey() {
            return organization + '\n' + repository + '\n' + prNumber + '\n' + commit;
        }
    }
}
