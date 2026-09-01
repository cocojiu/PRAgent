package com.repoguard.agent.github.checks;

import com.repoguard.agent.entity.GithubCheckRun;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.GithubCheckRunMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class GithubCheckRunReconciler {

    private final GithubCheckRunMapper checkRunMapper;
    private final ReviewTaskMapper taskMapper;
    private final GithubCheckRunClient client;
    private final GithubCheckRunOutcomeResolver outcomeResolver;
    private final GithubCheckRunProperties properties;
    private final String instanceId = "repoguard-checks-" + UUID.randomUUID();

    public GithubCheckRunReconciler(
        GithubCheckRunMapper checkRunMapper,
        ReviewTaskMapper taskMapper,
        GithubCheckRunClient client,
        GithubCheckRunOutcomeResolver outcomeResolver,
        GithubCheckRunProperties properties
    ) {
        this.checkRunMapper = Objects.requireNonNull(checkRunMapper, "checkRunMapper");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.client = Objects.requireNonNull(client, "client");
        this.outcomeResolver = Objects.requireNonNull(outcomeResolver, "outcomeResolver");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public void reconcileDue() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (GithubCheckRun record : checkRunMapper.selectDue(
            now,
            now.minusSeconds(properties.getClaimLeaseSeconds()),
            properties.getRecoveryBatchSize()
        )) {
            if (record == null || record.getId() == null) {
                continue;
            }
            LocalDateTime claimedAt = LocalDateTime.now();
            String claimId = instanceId + ":" + record.getId();
            if (checkRunMapper.claim(record.getId(), claimedAt, claimedAt.minusSeconds(properties.getClaimLeaseSeconds()), claimId) > 0) {
                reconcileClaimed(record.getId(), claimId);
            }
        }
    }

    public void reconcileClaimed(Long recordId, String claimId) {
        try {
            reconcile(recordId, claimId);
        } catch (RuntimeException ex) {
            GithubCheckRun record = checkRunMapper.selectById(recordId);
            if (record != null) {
                int attempts = record.getDispatchAttempts() == null ? 0 : record.getDispatchAttempts();
                long delay = Math.min(
                    properties.getRetryMaxSeconds(),
                    (long) properties.getRetryBaseSeconds() * (1L << Math.min(attempts, 6))
                );
                String message = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage();
                if (message.length() > 900) {
                    message = message.substring(0, 897) + "...";
                }
                checkRunMapper.markFailed(
                    recordId,
                    claimId,
                    LocalDateTime.now().plusSeconds(delay),
                    message,
                    LocalDateTime.now()
                );
            }
        }
    }

    private void reconcile(Long recordId, String claimId) {
        GithubCheckRun record = checkRunMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        ReviewTask task = taskMapper.selectById(record.getTaskId());
        if (task == null) {
            checkRunMapper.release(recordId, claimId, LocalDateTime.now());
            return;
        }
        GithubCheckRunStage desired = GithubCheckRunStage.from(record.getDesiredStage());
        GithubCheckRunStage applied = GithubCheckRunStage.from(record.getAppliedStage());
        if (record.getGithubCheckRunId() == null) {
            GithubCheckRunGateway.Output output = new GithubCheckRunGateway.Output(
                "RepoGuard 审查已排队", "审查任务已排队，等待执行。", null, java.util.List.of()
            );
            GithubCheckRunGateway.RemoteCheckRun remote = client.findOrCreate(task, record, output);
            checkRunMapper.markCreated(
                recordId,
                claimId,
                remote.id(),
                stageFromRemote(remote.status()).name(),
                LocalDateTime.now()
            );
            checkRunMapper.markApplied(
                recordId,
                claimId,
                stageFromRemote(remote.status()).name(),
                (long) stageFromRemote(remote.status()).rank(),
                LocalDateTime.now()
            );
            record = checkRunMapper.selectById(recordId);
            applied = GithubCheckRunStage.from(record.getAppliedStage());
            desired = GithubCheckRunStage.from(record.getDesiredStage());
        }
        if (desired.rank() >= GithubCheckRunStage.IN_PROGRESS.rank()
            && (applied == null || applied.rank() < GithubCheckRunStage.IN_PROGRESS.rank())) {
            client.update(
                task,
                record,
                new GithubCheckRunGateway.UpdateRequest(
                    GithubCheckRunStage.IN_PROGRESS.githubStatus(), null,
                    timestamp(record.getCreatedAt()), null,
                    new GithubCheckRunGateway.Output("RepoGuard 审查进行中", "正在分析 Pull Request 变更。", null, java.util.List.of())
                )
            );
            checkRunMapper.markApplied(recordId, claimId, GithubCheckRunStage.IN_PROGRESS.name(),
                record.getDesiredVersion(), LocalDateTime.now());
            record = checkRunMapper.selectById(recordId);
            applied = GithubCheckRunStage.from(record.getAppliedStage());
            desired = GithubCheckRunStage.from(record.getDesiredStage());
        }
        if (desired == GithubCheckRunStage.COMPLETED
            && (applied == null || applied.rank() < GithubCheckRunStage.COMPLETED.rank()
                || !Objects.equals(record.getAppliedVersion(), record.getDesiredVersion()))) {
            GithubCheckRunOutcomeResolver.Outcome outcome = outcomeResolver.resolve(task);
            updateCompleted(task, record, outcome);
            checkRunMapper.markApplied(recordId, claimId, GithubCheckRunStage.COMPLETED.name(),
                record.getDesiredVersion(), LocalDateTime.now());
        }
        checkRunMapper.release(recordId, claimId, LocalDateTime.now());
    }

    private void updateCompleted(
        ReviewTask task,
        GithubCheckRun record,
        GithubCheckRunOutcomeResolver.Outcome outcome
    ) {
        java.util.List<GithubCheckRunGateway.Annotation> annotations = outcome.annotations();
        int limit = properties.getAnnotationLimit();
        if (annotations.isEmpty()) {
            client.update(task, record, completedRequest(outcome, java.util.List.of()));
            return;
        }
        for (int offset = 0; offset < annotations.size(); offset += limit) {
            int end = Math.min(annotations.size(), offset + limit);
            client.update(task, record, completedRequest(outcome, annotations.subList(offset, end)));
        }
    }

    private GithubCheckRunGateway.UpdateRequest completedRequest(
        GithubCheckRunOutcomeResolver.Outcome outcome,
        java.util.List<GithubCheckRunGateway.Annotation> annotations
    ) {
        return new GithubCheckRunGateway.UpdateRequest(
            GithubCheckRunStage.COMPLETED.githubStatus(), outcome.conclusion(), null,
            timestamp(LocalDateTime.now()),
            new GithubCheckRunGateway.Output("RepoGuard PR Review", outcome.summary(), null, annotations)
        );
    }

    private GithubCheckRunStage stageFromRemote(String status) {
        if ("in_progress".equalsIgnoreCase(status)) {
            return GithubCheckRunStage.IN_PROGRESS;
        }
        if ("completed".equalsIgnoreCase(status)) {
            return GithubCheckRunStage.COMPLETED;
        }
        return GithubCheckRunStage.QUEUED;
    }

    private String timestamp(LocalDateTime value) {
        return value == null ? null : value.toString() + "Z";
    }
}
