package com.repoguard.agent.dashboard;

import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Bounded, traceable human-feedback observations, never an estimate of overall model quality. */
@Service
public class FeedbackSummaryService {
    static final int LIMIT = 1000;
    private final JdbcTemplate jdbc;

    public FeedbackSummaryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public FeedbackSummary summary() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(30);
        List<Observation> rows = jdbc.query("""
            select f.id, f.task_id, f.attempt_id, f.current_attempt, f.category, f.source,
                   f.finding_fingerprint, f.feedback_status, f.feedback_by, f.feedback_at,
                   t.current_attempt_id, t.organization, t.repository, t.pr_number, t.commit_sha,
                   t.source as task_source
              from review_finding f
              left join review_task t on t.id = f.task_id and t.tenant_id = f.tenant_id
             where f.tenant_id = ? and f.feedback_at >= ? and f.feedback_at <= ?
             order by f.feedback_at desc, f.id desc limit 1001
            """, (rs, n) -> new Observation(rs.getLong("id"), rs.getLong("task_id"),
                rs.getLong("attempt_id"), rs.getLong("current_attempt_id"), rs.getBoolean("current_attempt"),
                rs.getString("category"), rs.getString("source"), rs.getString("finding_fingerprint"),
                rs.getString("feedback_status"), rs.getString("feedback_by"),
                rs.getObject("feedback_at", LocalDateTime.class), rs.getString("organization"),
                rs.getString("repository"), rs.getInt("pr_number"), rs.getString("commit_sha"),
                rs.getString("task_source")), TenantContext.currentTenantIdOrDefault(), start, end);
        return summarize(rows, start, end);
    }

    static FeedbackSummary summarize(List<Observation> rows, LocalDateTime start, LocalDateTime end) {
        List<FeedbackDetail> accepted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int candidates = Math.min(rows.size(), LIMIT);
        for (Observation row : rows.subList(0, candidates)) {
            if (!row.eligible()) continue;
            String identity = row.organization() + "/" + row.repository() + "/" + row.prNumber()
                + "/" + row.headSha() + "/" + row.fingerprint();
            if (!seen.add(identity.toLowerCase(Locale.ROOT))) continue;
            accepted.add(new FeedbackDetail(row.id(), row.taskId(), row.source().toUpperCase(Locale.ROOT),
                row.status().toLowerCase(Locale.ROOT), row.actor(), row.at(),
                row.organization() + "/" + row.repository(), row.prNumber(), row.headSha()));
        }
        List<FeedbackSourceCount> sources = List.of(count("RULE", accepted), count("LLM", accepted));
        return new FeedbackSummary(start, end, candidates, candidates - accepted.size(), rows.size() > LIMIT,
            accepted.size(), sources, accepted.stream().limit(20).toList());
    }

    private static FeedbackSourceCount count(String source, List<FeedbackDetail> rows) {
        List<FeedbackDetail> selected = rows.stream().filter(row -> source.equals(row.source())).toList();
        return new FeedbackSourceCount(source, selected.size(), statusCount(selected, "valid"),
            statusCount(selected, "false_positive"), statusCount(selected, "fixed"), statusCount(selected, "ignored"),
            selected.size() < 30 ? "INSUFFICIENT_DATA" : "COUNTS_ONLY");
    }

    private static long statusCount(List<FeedbackDetail> rows, String status) {
        return rows.stream().filter(row -> status.equals(row.status())).count();
    }

    record Observation(long id, long taskId, long attemptId, long currentAttemptId, boolean current,
        String category, String source, String fingerprint, String status, String actor, LocalDateTime at,
        String organization, String repository, int prNumber, String headSha, String taskSource) {
        boolean eligible() {
            return current && attemptId > 0 && attemptId == currentAttemptId && "FINDING".equals(category)
                && Set.of("RULE", "LLM").contains(normalize(source))
                && Set.of("VALID", "FALSE_POSITIVE", "FIXED", "IGNORED").contains(normalize(status))
                && Set.of("MANUAL_INPUT", "GITHUB_PR_PICKER", "GITHUB_WEBHOOK", "EXISTING_REUSED").contains(normalize(taskSource))
                && actor != null && !actor.isBlank() && !Set.of("UNKNOWN", "SYSTEM", "DEMO").contains(normalize(actor))
                && fingerprint != null && !fingerprint.isBlank() && headSha != null && headSha.matches("[a-fA-F0-9]{40}")
                && organization != null && !organization.isBlank() && repository != null && !repository.isBlank() && prNumber > 0;
        }
        private static String normalize(String value) { return value == null ? "" : value.toUpperCase(Locale.ROOT); }
    }

    public record FeedbackSummary(LocalDateTime windowStart, LocalDateTime windowEnd, int examinedCount,
        int excludedOrDuplicateCount, boolean truncated, int sampleSize, List<FeedbackSourceCount> sources,
        List<FeedbackDetail> details) { }
    public record FeedbackSourceCount(String source, int reviewed, long valid, long falsePositive, long fixed,
        long ignored, String evidenceStatus) { }
    public record FeedbackDetail(long findingId, long taskId, String source, String status, String actor,
        LocalDateTime feedbackAt, String repository, int prNumber, String headSha) { }
}
