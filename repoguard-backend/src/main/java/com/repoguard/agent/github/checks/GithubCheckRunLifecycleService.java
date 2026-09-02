package com.repoguard.agent.github.checks;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.GithubCheckRun;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.GithubCheckRunMapper;
import com.repoguard.agent.review.ReviewTaskCheckRunLifecycle;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Records the desired Check Run state inside the same transaction as task state changes. */
@Component
public class GithubCheckRunLifecycleService implements ReviewTaskCheckRunLifecycle {

    private final GithubCheckRunMapper mapper;
    private final GithubCheckRunProperties properties;
    private final GithubCheckRunPolicyProvider policyProvider;

    @Autowired
    public GithubCheckRunLifecycleService(
        GithubCheckRunMapper mapper,
        GithubCheckRunProperties properties,
        GithubCheckRunPolicyProvider policyProvider
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.policyProvider = Objects.requireNonNull(policyProvider, "policyProvider");
    }

    public GithubCheckRunLifecycleService(
        GithubCheckRunMapper mapper,
        GithubCheckRunProperties properties
    ) {
        this(mapper, properties, task -> true);
    }

    public void queued(ReviewTask task) {
        if (!enabled(task)) {
            return;
        }
        GithubCheckRun current = mapper.selectLatestForTask(task.getId());
        if (current == null || GithubCheckRunStage.from(current.getDesiredStage()) == GithubCheckRunStage.COMPLETED) {
            insert(task, nextSequence(current), GithubCheckRunStage.QUEUED);
        }
    }

    public void inProgress(ReviewTask task) {
        if (!enabled(task)) {
            return;
        }
        GithubCheckRun current = ensureCurrent(task);
        GithubCheckRunStage desired = GithubCheckRunStage.from(current.getDesiredStage());
        if (desired.rank() < GithubCheckRunStage.IN_PROGRESS.rank()) {
            advance(current, GithubCheckRunStage.IN_PROGRESS);
        }
    }

    public void completed(ReviewTask task) {
        if (!enabled(task)) {
            return;
        }
        GithubCheckRun current = ensureCurrent(task);
        advance(current, GithubCheckRunStage.COMPLETED);
    }

    private GithubCheckRun ensureCurrent(ReviewTask task) {
        GithubCheckRun current = mapper.selectLatestForTask(task.getId());
        if (current == null || GithubCheckRunStage.from(current.getDesiredStage()) == GithubCheckRunStage.COMPLETED) {
            queued(task);
            current = mapper.selectLatestForTask(task.getId());
        }
        if (current == null) {
            throw new IllegalStateException("GitHub Check Run record could not be created for task " + task.getId());
        }
        return current;
    }

    private void advance(GithubCheckRun current, GithubCheckRunStage target) {
        long version = current.getDesiredVersion() == null ? 1L : current.getDesiredVersion();
        boolean terminalRepeat = target == GithubCheckRunStage.COMPLETED
            && target.name().equalsIgnoreCase(current.getDesiredStage());
        if (target.rank() < GithubCheckRunStage.from(current.getDesiredStage()).rank() && !terminalRepeat) {
            return;
        }
        UpdateWrapper<GithubCheckRun> update = new UpdateWrapper<GithubCheckRun>()
            .eq("id", current.getId())
            .set("desired_stage", target.name())
            .set("desired_version", version + 1L)
            .set("next_dispatch_at", LocalDateTime.now())
            .set("last_error", null)
            .set("updated_at", LocalDateTime.now());
        mapper.update(update);
    }

    private void insert(ReviewTask task, int sequence, GithubCheckRunStage stage) {
        if (!StringUtils.hasText(task.getCommitSha())) {
            throw new IllegalStateException("Review task commit SHA is required for GitHub Check Run");
        }
        GithubCheckRun record = new GithubCheckRun();
        record.setTaskId(task.getId());
        record.setRunSequence(sequence);
        record.setName(properties.getName().trim());
        record.setHeadSha(task.getCommitSha().trim());
        record.setExternalId("repoguard-task:" + task.getId() + ":run:" + sequence);
        record.setDesiredStage(stage.name());
        record.setDesiredVersion(1L);
        record.setAppliedVersion(0L);
        record.setDispatchAttempts(0);
        LocalDateTime now = LocalDateTime.now();
        record.setNextDispatchAt(now);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        try {
            mapper.insert(record);
        } catch (DuplicateKeyException duplicate) {
            // A duplicate webhook/retry raced this insert. The existing row is authoritative.
            if (mapper.selectLatestForTask(task.getId()) == null) {
                throw duplicate;
            }
        }
    }

    private int nextSequence(GithubCheckRun current) {
        return current == null || current.getRunSequence() == null ? 1 : current.getRunSequence() + 1;
    }

    private boolean enabled(ReviewTask task) {
        return properties.isEnabled()
            && task != null
            && task.getId() != null
            && policyProvider.isEnabled(task);
    }
}
