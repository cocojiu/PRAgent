package com.repoguard.agent.github.comment;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubCommentPublicationStatus;
import com.repoguard.agent.github.GithubPublicationIntent;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes only durable state transitions; no database lock is held across HTTP. */
@Component
public class GithubPublicationProtocol {
    private final GithubCommentPublicationMapper publications;
    private final ReviewTaskMapper tasks;
    private final GithubCommentPublicationRecorder recorder;
    public GithubPublicationProtocol(GithubCommentPublicationMapper publications, ReviewTaskMapper tasks,
        GithubCommentPublicationRecorder recorder) {
        this.publications = publications; this.tasks = tasks; this.recorder = recorder;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Claim> claim(ReviewTask task, List<GithubReviewCommentDraft> drafts) {
        ReviewTask locked = lock(task.getId());
        if (task.getCommitSha() == null || task.getCommitSha().isBlank() || !Objects.equals(locked.getCommitSha(), task.getCommitSha()))
            throw new BusinessException(ErrorCode.CONFLICT, "Task commit changed before publication");
        List<Claim> claims = new ArrayList<>();
        for (var draft : drafts) {
            QueryWrapper<GithubCommentPublication> query = new QueryWrapper<GithubCommentPublication>().eq("task_id", task.getId());
            if (draft.findingId() == null) query.isNull("finding_id"); else query.eq("finding_id", draft.findingId());
            var existing = publications.selectOne(query.last("order by published_success desc, (status in ('outcome_unknown','closed')) desc, id desc limit 1"));
            if (existing != null && (Boolean.TRUE.equals(existing.getSuccess())
                || List.of("outcome_unknown", "closed").contains(existing.getStatus()))) {
                claims.add(new Claim(task.getId(), draft, existing, null));
                continue;
            }
            String marker = GithubPublicationIntent.create(task.getCommitSha(), draft.body());
            String message = GithubPublicationIntent.message(marker, GithubCommentPublicationStatus.VERIFICATION_MESSAGE);
            var publication = recorder.recordPublication(task.getId(), new GithubReviewCommentResult(draft.findingId(),
                draft.path(), draft.line(), draft.targetType(), false, "outcome_unknown", message, null, null));
            claims.add(new Claim(task.getId(), draft, publication, message));
        }
        return List.copyOf(claims);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GithubCommentPublication finish(Claim claim, GithubReviewCommentResult result) {
        lock(claim.taskId());
        var publication = load(claim.taskId(), claim.publication().getId());
        if (!GithubPublicationIntent.pending(publication.getStatus(), publication.getMessage()) || !Objects.equals(publication.getMessage(), claim.expectedMessage())) return publication;
        return write(publication, result, claim.expectedMessage());
    }
    public ReviewTask task(Long taskId) {
        ReviewTask task = tasks.selectById(taskId);
        if (task == null) throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found");
        return task;
    }
    public GithubCommentPublication load(Long taskId, Long publicationId) {
        var publication = publications.selectOne(new QueryWrapper<GithubCommentPublication>().eq("id", publicationId).eq("task_id", taskId));
        if (publication == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Publication not found");
        return publication;
    }
    public List<GithubCommentPublication> unresolved(Long taskId, long afterId, int limit) {
        task(taskId);
        return publications.selectList(new QueryWrapper<GithubCommentPublication>().eq("task_id", taskId)
            .eq("status", "outcome_unknown").and(query -> query.isNull("message").or().notLikeRight("message", GithubPublicationIntent.CLOSED_PREFIX)).gt("id", afterId).orderByAsc("id").last("limit " + Math.max(1, Math.min(50, limit))));
    }
    public static String version(GithubCommentPublication publication) {
        return GithubPublicationIntent.hash(publication.getId() + ":" + publication.getStatus() + ":" + publication.getMessage());
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GithubCommentPublication resolve(Long taskId, Long publicationId, String expectedVersion, GithubReviewCommentResult result) {
        lock(taskId);
        var publication = load(taskId, publicationId);
        if (!GithubPublicationIntent.pending(publication.getStatus(), publication.getMessage()) || !version(publication).equals(expectedVersion))
            throw new BusinessException(ErrorCode.CONFLICT, "Publication changed; reload before resolving");
        return write(publication, result, publication.getMessage());
    }
    private ReviewTask lock(Long taskId) {
        ReviewTask task = tasks.selectGithubPublishTaskForUpdate(taskId);
        if (task == null) throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found");
        return task;
    }
    private GithubCommentPublication write(GithubCommentPublication publication, GithubReviewCommentResult result, String expectedMessage) {
        String marker = GithubPublicationIntent.marker(expectedMessage);
        String message = "outcome_unknown".equals(result.status())
            ? GithubPublicationIntent.message(marker, result.message()) : result.message();
        String storedStatus = result.status();
        if ("closed".equals(storedStatus)) {
            storedStatus = "outcome_unknown";
            message = GithubPublicationIntent.CLOSED_PREFIX + GithubPublicationIntent.message(marker, result.message());
            message = message.substring(0, Math.min(message.length(), 1024));
        }
        LocalDateTime now = LocalDateTime.now();
        int changed = publications.update(new UpdateWrapper<GithubCommentPublication>().eq("id", publication.getId())
            .eq("task_id", publication.getTaskId()).eq("status", "outcome_unknown")
            .apply("cast(message as binary) <=> cast({0} as binary)", expectedMessage)
            .set("status", storedStatus).set("success", result.success()).set("target_type", result.targetType())
            .set("github_comment_id", result.commentId()).set("github_url", result.url()).set("message", message)
            .set("published_at", Boolean.TRUE.equals(result.success()) ? now : null).set("updated_at", now));
        if (changed != 1) throw new BusinessException(ErrorCode.CONFLICT, "Publication ownership changed");
        return load(publication.getTaskId(), publication.getId());
    }
    public record Claim(Long taskId, GithubReviewCommentDraft draft, GithubCommentPublication publication, String expectedMessage) {
        public boolean granted() { return expectedMessage != null; }
        public GithubReviewCommentDraft markedDraft() {
            return new GithubReviewCommentDraft(draft.findingId(), draft.path(), draft.line(), draft.body() + "\n\n"
                + GithubPublicationIntent.marker(expectedMessage), draft.targetType());
        }
    }
}
