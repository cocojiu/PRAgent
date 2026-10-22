package com.repoguard.agent.review.execution;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewAttemptComparisonDto;
import com.repoguard.agent.dto.ReviewFindingComparisonSummaryDto;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewFindingIdentity;
import com.repoguard.agent.review.execution.ReviewFindingComparisonCalculator.Entry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.factory.annotation.Value;
import org.apache.ibatis.exceptions.PersistenceException;
import org.mybatis.spring.MyBatisExceptionTranslator;
import org.mybatis.spring.SqlSessionHolder;

/** Read-only comparison queries and an explicit, fenced current-attempt materialization command. */
@Service
public class ReviewFindingComparisonService {
    private static final int MAX_PAGE_SIZE = 100;
    private final ReviewTaskMapper taskMapper;
    private final ReviewExecutionAttemptMapper attemptMapper;
    private final ReviewFindingMapper findingMapper;
    private MeterRegistry metrics;
    private final SqlSessionFactory batchSessions;
    @Value("${repoguard.review.comparison.batch-writes-enabled:true}")
    private boolean batchWritesEnabled = true;

    @Autowired(required = false)
    void recordMetrics(MeterRegistry registry) { metrics = registry; }

    public ReviewFindingComparisonService(ReviewTaskMapper taskMapper, ReviewExecutionAttemptMapper attemptMapper,
        ReviewFindingMapper findingMapper) {
        this(taskMapper, attemptMapper, findingMapper, null);
    }

    @Autowired
    public ReviewFindingComparisonService(ReviewTaskMapper taskMapper, ReviewExecutionAttemptMapper attemptMapper,
        ReviewFindingMapper findingMapper, SqlSessionFactory batchSessions) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper");
        this.findingMapper = Objects.requireNonNull(findingMapper, "findingMapper");
        this.batchSessions = batchSessions;
    }

    @Transactional(readOnly = true)
    public ReviewAttemptComparisonDto compare(Long taskId, Long baselineAttemptId, Long candidateAttemptId,
        int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid finding comparison page");
        if (taskMapper.selectById(taskId) == null)
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        return calculate(taskId, baselineAttemptId, candidateAttemptId).page(taskId, page, pageSize);
    }

    @Transactional
    public void materializeCurrent(Long taskId, Long candidateAttemptId) {
        long startedAt = System.nanoTime();
        if (metrics != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    metrics.timer("repoguard.review.comparison.transaction.duration", "outcome",
                        status == STATUS_COMMITTED ? "committed" : "rolled_back")
                        .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
                }
            });
        }
        ReviewTask task = taskMapper.selectComparisonTaskForUpdate(taskId);
        if (metrics != null) metrics.timer("repoguard.review.comparison.task_lock.duration")
            .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        if (task == null || !Objects.equals(candidateAttemptId, task.getCurrentAttemptId()))
            throw new BusinessException(ErrorCode.CONFLICT, "Current review attempt changed before comparison");
        Calculation calculation = calculate(taskId, null, candidateAttemptId);
        if (metrics != null) metrics.summary("repoguard.review.comparison.entries").record(calculation.entries().size());
        List<Entry> pending = new ArrayList<>();
        for (Entry entry : calculation.entries()) {
            ReviewFinding finding = entry.finding();
            if (finding.getId() == null || finding.getAttemptId() == null)
                throw new BusinessException(ErrorCode.CONFLICT, "Comparison finding identity is missing");
            if (alreadyMaterialized(finding, entry, candidateAttemptId)) continue;
            pending.add(entry);
        }
        if (batchSessions != null && batchWritesEnabled && TransactionSynchronizationManager.isActualTransactionActive()) {
            persistBatch(taskId, candidateAttemptId, pending);
        } else {
            for (Entry entry : pending) requireChanged(write(findingMapper, taskId, candidateAttemptId, entry));
        }
        if (metrics != null) metrics.summary("repoguard.review.comparison.writes").record(pending.size());
    }

    private void persistBatch(Long taskId, Long candidate, List<Entry> entries) {
        if (entries.isEmpty()) return;
        try (SqlSession session = batchSessions.openSession(ExecutorType.BATCH)) {
            ReviewFindingMapper mapper = session.getMapper(ReviewFindingMapper.class);
            int pending = 0;
            for (Entry entry : entries) {
                write(mapper, taskId, candidate, entry);
                if (++pending == 100) {
                    flushChecked(session, pending);
                    pending = 0;
                }
            }
            if (pending > 0) flushChecked(session, pending);
            // SpringManagedTransaction retains the surrounding task lock and owns the actual commit.
            session.commit();
        } catch (PersistenceException failure) {
            var translated = new MyBatisExceptionTranslator(
                batchSessions.getConfiguration().getEnvironment().getDataSource(), true)
                .translateExceptionIfPossible(failure);
            if (translated != null) throw translated;
            throw failure;
        }
        // Batch and read sessions share a transaction, but not their first-level caches.
        Object current = TransactionSynchronizationManager.getResource(batchSessions);
        if (current instanceof SqlSessionHolder holder) {
            holder.getSqlSession().clearCache();
        }
    }

    private static void flushChecked(SqlSession session, int expected) {
        int observed = 0;
        for (var batch : session.flushStatements()) {
            for (int changed : batch.getUpdateCounts()) {
                requireChanged(changed);
                observed++;
            }
        }
        if (observed != expected) throw new BusinessException(ErrorCode.CONFLICT, "Comparison batch update count is incomplete");
    }

    private static int write(ReviewFindingMapper mapper, Long taskId, Long candidate, Entry entry) {
        ReviewFinding finding = entry.finding();
        return mapper.updateComparison(finding.getId(), taskId, finding.getAttemptId(), entry.fingerprint(),
            entry.baselineFindingId(), entry.status(), entry.confidence(), entry.reason(),
            ReviewFindingIdentity.VERSION, candidate, finding.getComparisonAttemptId());
    }

    private static void requireChanged(int changed) {
        // Unknown JDBC batch counts are not evidence that the per-row version fence succeeded.
        if (changed != 1) throw new BusinessException(ErrorCode.CONFLICT, "Comparison result changed or its write could not be confirmed");
    }

    private static boolean alreadyMaterialized(ReviewFinding finding, Entry entry, Long candidate) {
        return Objects.equals(candidate, finding.getComparisonAttemptId())
            && Objects.equals(entry.fingerprint(), finding.getFindingFingerprint())
            && Objects.equals(entry.baselineFindingId(), finding.getPreviousFindingId())
            && Objects.equals(entry.status(), finding.getComparisonStatus())
            && Objects.equals(entry.reason(), finding.getComparisonReason())
            && Objects.equals(ReviewFindingIdentity.VERSION, finding.getComparisonVersion())
            && finding.getComparisonConfidence() != null && entry.confidence().compareTo(finding.getComparisonConfidence()) == 0;
    }

    private Calculation calculate(Long taskId, Long requestedBaseline, Long candidateAttemptId) {
        ReviewExecutionAttempt candidate = requireAttempt(taskId, candidateAttemptId);
        if (!successful(candidate))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only a completed or partial attempt can be compared");
        ReviewExecutionAttempt baseline = requestedBaseline == null
            ? attemptMapper.selectPreviousSuccessful(taskId, candidate.getAttemptNo()) : requireAttempt(taskId, requestedBaseline);
        if (baseline != null && (!successful(baseline) || baseline.getAttemptNo() == null
            || candidate.getAttemptNo() == null || baseline.getAttemptNo() >= candidate.getAttemptNo()))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Baseline attempt must be an earlier completed attempt");
        List<ReviewFinding> current = findings(taskId, candidate.getId());
        if (baseline == null) return new Calculation(null, candidate, true, "NO_PREVIOUS_SUCCESSFUL_ATTEMPT",
            ReviewFindingComparisonCalculator.standalone(taskId, current, "NO_PREVIOUS_SUCCESSFUL_ATTEMPT", "NEW"));
        List<ReviewFinding> previous = findings(taskId, baseline.getId());
        if (!versionKey(baseline).equals(versionKey(candidate)) || !previous.isEmpty() && !current.isEmpty()
            && !metadata(previous).equals(metadata(current)))
            return new Calculation(baseline, candidate, false, "STRATEGY_VERSION_CHANGED",
                ReviewFindingComparisonCalculator.standalone(taskId, current, "STRATEGY_VERSION_CHANGED", "UNMATCHED"));
        List<String> fingerprints = current.stream().map(finding -> ReviewFindingComparisonCalculator.fingerprint(taskId, finding))
            .filter(Objects::nonNull).distinct().toList();
        List<ReviewFinding> history = new ArrayList<>();
        List<ReviewFinding> legacy = findingMapper.selectEarlierLegacyFingerprints(taskId, baseline.getAttemptNo());
        if (legacy.size() > 200) return new Calculation(baseline, candidate, false, "LEGACY_HISTORY_NOT_INDEXED",
            ReviewFindingComparisonCalculator.standalone(taskId, current, "LEGACY_HISTORY_NOT_INDEXED", "UNMATCHED"));
        for (int from = 0; from < fingerprints.size(); from += 200) {
            history.addAll(findingMapper.selectEarlierFingerprintMatches(taskId, baseline.getAttemptNo(),
                fingerprints.subList(from, Math.min(from + 200, fingerprints.size()))));
        }
        history.addAll(legacy);
        return new Calculation(baseline, candidate, true, "STABLE_FINGERPRINT_COMPARISON",
            ReviewFindingComparisonCalculator.compare(taskId, previous, current, history));
    }

    private List<ReviewFinding> findings(Long taskId, Long attemptId) {
        List<ReviewFinding> findings = findingMapper.selectAttemptForComparison(taskId, attemptId);
        if (findings != null && findings.size() > 5000)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Finding comparison exceeds 5000 findings per attempt");
        return findings == null ? List.of() : findings;
    }

    private ReviewExecutionAttempt requireAttempt(Long taskId, Long id) {
        ReviewExecutionAttempt attempt = id == null ? null : attemptMapper.selectById(id);
        if (attempt == null || !Objects.equals(taskId, attempt.getTaskId()))
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review execution attempt not found: " + id);
        return attempt;
    }

    private static boolean successful(ReviewExecutionAttempt attempt) {
        return attempt != null && ("COMPLETED".equalsIgnoreCase(attempt.getStatus()) || "PARTIAL".equalsIgnoreCase(attempt.getStatus()));
    }

    private static List<String> versionKey(ReviewExecutionAttempt attempt) {
        return List.of(safe(attempt.getPolicyVersion()), safe(attempt.getPromptVersion()), safe(attempt.getContextVersion()),
            safe(attempt.getSchemaVersion()), safe(attempt.getVerifierVersion()), safe(attempt.getAggregationVersion()));
    }

    private static Set<List<String>> metadata(List<ReviewFinding> findings) {
        Set<List<String>> keys = new HashSet<>();
        findings.forEach(finding -> keys.add(List.of(safe(finding.getDetectorVersion()), safe(finding.getRuleConfigVersion()),
            safe(finding.getLlmProvider()), safe(finding.getLlmModel()))));
        return keys;
    }

    private static String safe(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private record Calculation(ReviewExecutionAttempt baseline, ReviewExecutionAttempt candidate, boolean comparable,
        String reason, List<Entry> entries) {
        ReviewAttemptComparisonDto page(Long taskId, int page, int pageSize) {
            List<Entry> sorted = entries.stream().sorted(Comparator.comparingInt((Entry entry) ->
                switch (entry.status()) {
                    case "NEW" -> 0;
                    case "REGRESSED" -> 1;
                    case "PERSISTING" -> 2;
                    case "UNMATCHED" -> 3;
                    default -> 4;
                }).thenComparing(entry -> entry.finding().getId(), Comparator.nullsLast(Comparator.naturalOrder()))).toList();
            long offset = (long) (page - 1) * pageSize;
            int from = (int) Math.min(offset, sorted.size());
            int to = Math.min(from + pageSize, sorted.size());
            boolean more = to < sorted.size();
            var summary = new ReviewFindingComparisonSummaryDto(count("NEW"), count("PERSISTING"), count("RESOLVED"),
                count("REGRESSED"), count("UNMATCHED"), entries.size());
            return new ReviewAttemptComparisonDto(taskId, baseline == null ? null : baseline.getId(), candidate.getId(),
                baseline == null ? null : baseline.getCommitSha(), candidate.getCommitSha(), comparable, reason, summary,
                new PageResponse<>(sorted.subList(from, to).stream().map(Entry::toDto).toList(), sorted.size(),
                    more ? String.valueOf(page + 1) : null, more));
        }
        private long count(String status) { return entries.stream().filter(entry -> status.equals(entry.status())).count(); }
    }
}
