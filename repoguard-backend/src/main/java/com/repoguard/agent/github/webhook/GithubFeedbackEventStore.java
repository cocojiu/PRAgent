package com.repoguard.agent.github.webhook;

import com.repoguard.agent.tenancy.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All queries are tenant scoped, including joins used to resolve publication ownership. */
@Repository
public class GithubFeedbackEventStore {
    private final JdbcTemplate jdbc;

    public GithubFeedbackEventStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Target target(GithubFeedbackCommand command, boolean lock) {
        List<Target> rows = jdbc.query("""
            select t.id as task_id, f.id as finding_id, t.current_attempt_id,
                   f.feedback_at, f.feedback_status
              from github_comment_publication p
              join review_task t on t.id = p.task_id and t.tenant_id = p.tenant_id
              join review_finding f on f.id = p.finding_id and f.task_id = t.id and f.tenant_id = t.tenant_id
             where p.tenant_id = ? and p.github_comment_id = ? and p.success = true
               and p.target_type = 'line' and p.status = 'published'
               and t.organization = ? and t.repository = ? and t.pr_number = ? and t.commit_sha = ?
               and f.category = 'FINDING' and f.current_attempt = true and f.attempt_id = t.current_attempt_id
             limit 2
            """ + (lock ? " for update" : ""), (rs, n) -> new Target(rs.getLong("task_id"),
                rs.getLong("finding_id"), rs.getLong("current_attempt_id"), time(rs, "feedback_at"),
                rs.getString("feedback_status")), tenant(), command.parentCommentId(), command.owner(),
                command.repository(), command.prNumber(), command.headSha());
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    public boolean insert(String deliveryId, GithubFeedbackCommand c, Target target) {
        try {
            jdbc.update("""
                insert into github_feedback_event
                  (tenant_id, delivery_id, owner, repository, pr_number, head_sha, comment_id, parent_comment_id,
                   actor_id, actor, feedback_status, note, body_hash, event_created_at, task_id, finding_id, attempt_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenant(), deliveryId, c.owner(), c.repository(), c.prNumber(), c.headSha(), c.commentId(),
                c.parentCommentId(), c.actorId(), c.actor(), c.feedbackStatus(), c.note(), c.bodyHash(), c.createdAt(),
                target.taskId(), target.findingId(), target.attemptId());
            return true;
        } catch (DuplicateKeyException ex) { return false; }
    }

    public List<Event> due() {
        return jdbc.query("""
            select * from github_feedback_event
             where tenant_id = ? and status = 'PENDING' and next_attempt_at <= current_timestamp(6)
             order by next_attempt_at, id limit 10
            """, this::map, tenant());
    }

    public Event lock(long id) {
        List<Event> rows = jdbc.query("select * from github_feedback_event where tenant_id = ? and id = ? for update",
            this::map, tenant(), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void finish(long id, String status, String failure) {
        jdbc.update("""
            update github_feedback_event set status = ?, failure_code = ?, updated_at = current_timestamp(6)
             where tenant_id = ? and id = ? and status = 'PENDING'
            """, status, failure, tenant(), id);
    }

    public void retryLater(long id) {
        jdbc.update("""
            update github_feedback_event set status = if(attempts >= 4, 'FAILED', 'PENDING'),
                   attempts = attempts + 1, failure_code = 'PROCESSING_FAILED',
                   next_attempt_at = timestampadd(second, 60, current_timestamp(6)), updated_at = current_timestamp(6)
             where tenant_id = ? and id = ? and status = 'PENDING'
            """, tenant(), id);
    }

    public List<EventSummary> list(int limit) {
        return jdbc.query("""
            select id, delivery_id, task_id, finding_id, feedback_status, status, attempts, failure_code, updated_at
              from github_feedback_event where tenant_id = ? order by id desc limit ?
            """, (rs, n) -> new EventSummary(rs.getLong("id"), rs.getString("delivery_id"),
                rs.getLong("task_id"), rs.getLong("finding_id"), rs.getString("feedback_status"),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("failure_code"), time(rs, "updated_at")),
            tenant(), limit);
    }

    public boolean retry(long id) {
        return jdbc.update("""
            update github_feedback_event set status = 'PENDING', attempts = 0,
                   next_attempt_at = current_timestamp(6), updated_at = current_timestamp(6)
             where tenant_id = ? and id = ? and status = 'FAILED'
            """, tenant(), id) == 1;
    }

    private Event map(ResultSet rs, int row) throws SQLException {
        return new Event(rs.getLong("id"), new GithubFeedbackCommand(rs.getString("owner"), rs.getString("repository"),
            rs.getInt("pr_number"), rs.getString("head_sha"), rs.getLong("comment_id"), rs.getLong("parent_comment_id"),
            rs.getLong("actor_id"), rs.getString("actor"), rs.getString("feedback_status"), rs.getString("note"),
            rs.getString("body_hash"), time(rs, "event_created_at"), null), rs.getLong("task_id"),
            rs.getLong("finding_id"), rs.getLong("attempt_id"), rs.getString("status"));
    }

    private long tenant() { return TenantContext.currentTenantIdOrDefault(); }
    private static LocalDateTime time(ResultSet rs, String name) throws SQLException {
        return rs.getTimestamp(name) == null ? null : rs.getTimestamp(name).toLocalDateTime();
    }

    public record Target(long taskId, long findingId, long attemptId, LocalDateTime feedbackAt, String feedbackStatus) { }
    public record Event(long id, GithubFeedbackCommand command, long taskId, long findingId, long attemptId, String status) { }
    public record EventSummary(long id, String deliveryId, long taskId, long findingId, String feedbackStatus,
        String status, int attempts, String failureCode, LocalDateTime updatedAt) { }
}
