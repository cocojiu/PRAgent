package com.repoguard.agent.review.execution;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewAttemptComparisonDto;
import com.repoguard.agent.dto.ReviewFindingComparisonDto;
import com.repoguard.agent.dto.ReviewFindingComparisonSummaryDto;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.FindingFeedbackStatus;
import com.repoguard.agent.review.ReviewFindingIdentity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Compares adjacent successful attempts without changing feedback decisions. */
@Service
public class ReviewFindingComparisonService {
    private static final int MAX_ATTEMPTS = 100;
    private static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal FULL_CONFIDENCE = BigDecimal.ONE;
    private static final BigDecimal NO_CONFIDENCE = BigDecimal.ZERO;
    private final ReviewTaskMapper taskMapper;
    private final ReviewExecutionAttemptMapper attemptMapper;
    private final ReviewFindingMapper findingMapper;

    public ReviewFindingComparisonService(ReviewTaskMapper taskMapper, ReviewExecutionAttemptMapper attemptMapper,
        ReviewFindingMapper findingMapper) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper must not be null");
        this.findingMapper = Objects.requireNonNull(findingMapper, "findingMapper must not be null");
    }

    @Transactional
    public ReviewAttemptComparisonDto compare(Long taskId, Long baselineAttemptId, Long candidateAttemptId,
        int page, int pageSize) {
        validatePage(page, pageSize);
        requireTask(taskId);
        ReviewExecutionAttempt candidate = requireAttempt(taskId, candidateAttemptId);
        if (!successful(candidate)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only a completed or partial attempt can be compared");
        }
        List<ReviewExecutionAttempt> attempts = attemptMapper.selectByTaskId(taskId, MAX_ATTEMPTS);
        attempts = attempts == null ? List.of() : attempts;
        ReviewExecutionAttempt baseline = resolveBaseline(taskId, baselineAttemptId, candidate, attempts);
        List<ReviewFinding> all = findingMapper.selectByTaskIdForComparison(taskId);
        all = all == null ? List.of() : all;
        List<ReviewFinding> current = findingsFor(all, candidate.getId());
        List<ReviewFinding> previous = baseline == null ? List.of() : findingsFor(all, baseline.getId());
        Map<Long, Integer> numbers = attemptNumbers(attempts, baseline, candidate);
        if (baseline == null) {
            return finish(taskId, null, candidate, current, standalone(taskId, candidate, current,
                "NO_PREVIOUS_SUCCESSFUL_ATTEMPT", "NEW", FULL_CONFIDENCE), true,
                "NO_PREVIOUS_SUCCESSFUL_ATTEMPT", page, pageSize);
        }
        if (!compatible(baseline, candidate, previous, current)) {
            return finish(taskId, baseline, candidate, current, standalone(taskId, candidate, current,
                "STRATEGY_VERSION_CHANGED", "UNMATCHED", NO_CONFIDENCE), false,
                "STRATEGY_VERSION_CHANGED", page, pageSize);
        }
        return compareCompatible(taskId, baseline, candidate, previous, current, all, numbers, page, pageSize);
    }

    private ReviewAttemptComparisonDto compareCompatible(Long taskId, ReviewExecutionAttempt baseline,
        ReviewExecutionAttempt candidate, List<ReviewFinding> previous, List<ReviewFinding> current,
        List<ReviewFinding> all, Map<Long, Integer> numbers, int page, int pageSize) {
        Map<String, ReviewFinding> previousByFingerprint = new LinkedHashMap<>();
        for (ReviewFinding finding : previous) previousByFingerprint.putIfAbsent(ensureFingerprint(taskId, finding), finding);
        Map<String, ReviewFinding> resolvedHistory = new LinkedHashMap<>();
        for (ReviewFinding finding : all) {
            if ("RESOLVED".equalsIgnoreCase(finding.getComparisonStatus())
                && before(finding, candidate, numbers)) resolvedHistory.putIfAbsent(ensureFingerprint(taskId, finding), finding);
        }
        Set<String> seen = new HashSet<>();
        Set<Long> matched = new HashSet<>();
        List<ComparisonEntry> entries = new ArrayList<>();
        for (ReviewFinding finding : current) {
            String fingerprint = ensureFingerprint(taskId, finding);
            ComparisonEntry entry;
            if (!StringUtils.hasText(fingerprint)) entry = classify(finding, null, "MISSING_FINGERPRINT", "UNMATCHED", NO_CONFIDENCE);
            else if (!seen.add(fingerprint)) entry = classify(finding, null, "DUPLICATE_FINGERPRINT", "UNMATCHED", NO_CONFIDENCE);
            else {
                ReviewFinding old = previousByFingerprint.get(fingerprint);
                if (old != null) {
                    matched.add(old.getId());
                    boolean regressed = "RESOLVED".equalsIgnoreCase(old.getComparisonStatus());
                    entry = classify(finding, old, regressed ? "REAPPEARED_AFTER_RESOLUTION" : "STABLE_FINGERPRINT_MATCH",
                        regressed ? "REGRESSED" : "PERSISTING", FULL_CONFIDENCE);
                } else if ((old = resolvedHistory.get(fingerprint)) != null) {
                    entry = classify(finding, old, "REAPPEARED_AFTER_RESOLUTION", "REGRESSED", FULL_CONFIDENCE);
                } else if (hasLocationIndependentMatch(finding, previous, taskId)) {
                    entry = classify(finding, null, "LOCATION_CHANGED_OR_CROSS_FILE", "UNMATCHED", NO_CONFIDENCE);
                } else entry = classify(finding, null, "NO_MATCHING_PREVIOUS_FINGERPRINT", "NEW", FULL_CONFIDENCE);
            }
            persist(taskId, candidate.getId(), entry);
            entries.add(entry);
        }
        for (ReviewFinding old : previous) {
            if (old.getId() == null || matched.contains(old.getId())) continue;
            ensureFingerprint(taskId, old);
            ComparisonEntry resolved = new ComparisonEntry(old, old.getPreviousFindingId(), "RESOLVED", FULL_CONFIDENCE,
                "NOT_PRESENT_IN_CANDIDATE");
            persist(taskId, candidate.getId(), resolved);
            entries.add(resolved);
        }
        return finish(taskId, baseline, candidate, current, entries, true,
            "STABLE_FINGERPRINT_COMPARISON", page, pageSize);
    }

    private List<ComparisonEntry> standalone(Long taskId, ReviewExecutionAttempt candidate, List<ReviewFinding> findings,
        String reason, String status, BigDecimal confidence) {
        List<ComparisonEntry> entries = new ArrayList<>();
        for (ReviewFinding finding : findings) {
            ComparisonEntry entry = classify(finding, null, reason, status, confidence);
            persist(taskId, candidate.getId(), entry);
            entries.add(entry);
        }
        return entries;
    }

    private ReviewAttemptComparisonDto finish(Long taskId, ReviewExecutionAttempt baseline, ReviewExecutionAttempt candidate,
        List<ReviewFinding> current, List<ComparisonEntry> entries, boolean comparable, String reason, int page, int pageSize) {
        if (entries.isEmpty() && !current.isEmpty()) entries = standalone(taskId, candidate, current, reason,
            comparable ? "NEW" : "UNMATCHED", comparable ? FULL_CONFIDENCE : NO_CONFIDENCE);
        entries.sort(Comparator.comparingInt((ComparisonEntry e) -> statusOrder(e.status()))
            .thenComparingLong(e -> e.finding().getId() == null ? Long.MAX_VALUE : e.finding().getId()));
        ReviewFindingComparisonSummaryDto summary = summary(entries);
        long offset = (long) (page - 1) * pageSize;
        int from = offset >= entries.size() ? entries.size() : (int) offset;
        int to = Math.min(from + pageSize, entries.size());
        boolean hasMore = to < entries.size();
        List<ReviewFindingComparisonDto> items = entries.subList(from, to).stream().map(ComparisonEntry::toDto).toList();
        return new ReviewAttemptComparisonDto(taskId, baseline == null ? null : baseline.getId(), candidate.getId(),
            baseline == null ? null : baseline.getCommitSha(), candidate.getCommitSha(), comparable, reason, summary,
            new PageResponse<>(items, entries.size(), hasMore ? String.valueOf(page + 1) : null, hasMore));
    }

    private ComparisonEntry classify(ReviewFinding finding, ReviewFinding previous, String reason, String status,
        BigDecimal confidence) {
        return new ComparisonEntry(finding, previous == null ? null : previous.getId(), status, confidence, reason);
    }

    private void persist(Long taskId, Long candidateAttemptId, ComparisonEntry entry) {
        ReviewFinding finding = entry.finding();
        if (finding.getId() == null || finding.getAttemptId() == null) return;
        finding.setComparisonStatus(entry.status());
        finding.setComparisonConfidence(entry.confidence());
        finding.setComparisonReason(entry.reason());
        finding.setComparisonVersion(ReviewFindingIdentity.VERSION);
        finding.setPreviousFindingId(entry.baselineFindingId());
        finding.setComparisonAttemptId(candidateAttemptId);
        findingMapper.updateComparison(finding.getId(), taskId, finding.getAttemptId(), finding.getFindingFingerprint(),
            entry.baselineFindingId(), entry.status(), entry.confidence(), entry.reason(),
            ReviewFindingIdentity.VERSION, candidateAttemptId);
    }

    private boolean compatible(ReviewExecutionAttempt baseline, ReviewExecutionAttempt candidate,
        List<ReviewFinding> previous, List<ReviewFinding> current) {
        if (!versionKey(baseline, List.of()).equals(versionKey(candidate, List.of()))) return false;
        return previous.isEmpty() || current.isEmpty() || findingMetadataKey(previous).equals(findingMetadataKey(current));
    }

    private String versionKey(ReviewExecutionAttempt attempt, List<ReviewFinding> findings) {
        List<String> versions = new ArrayList<>(List.of(safe(attempt.getPolicyVersion()), safe(attempt.getPromptVersion()),
            safe(attempt.getContextVersion()), safe(attempt.getSchemaVersion()), safe(attempt.getVerifierVersion()),
            safe(attempt.getAggregationVersion())));
        findings.stream().map(this::findingMetadata).distinct().sorted().forEach(versions::add);
        return String.join("\u001f", versions);
    }

    private String findingMetadataKey(List<ReviewFinding> findings) {
        return findings.stream().map(this::findingMetadata).distinct().sorted().reduce((a, b) -> a + "\u001f" + b).orElse("");
    }

    private String findingMetadata(ReviewFinding finding) {
        return String.join("|", safe(finding.getDetectorVersion()), safe(finding.getRuleConfigVersion()),
            safe(finding.getLlmProvider()), safe(finding.getLlmModel()));
    }

    private boolean hasLocationIndependentMatch(ReviewFinding finding, List<ReviewFinding> previous, Long taskId) {
        String key = ReviewFindingIdentity.locationIndependentKey(taskId, finding);
        return previous.stream().anyMatch(old -> Objects.equals(key, ReviewFindingIdentity.locationIndependentKey(taskId, old)));
    }

    private ReviewExecutionAttempt resolveBaseline(Long taskId, Long requested, ReviewExecutionAttempt candidate,
        List<ReviewExecutionAttempt> attempts) {
        if (requested != null) {
            ReviewExecutionAttempt baseline = requireAttempt(taskId, requested);
            if (!successful(baseline) || !before(baseline, candidate)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Baseline attempt must be an earlier completed attempt");
            }
            return baseline;
        }
        return attempts.stream().filter(ReviewFindingComparisonService::successful).filter(old -> before(old, candidate))
            .max(Comparator.comparing(ReviewExecutionAttempt::getAttemptNo,
                Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(null);
    }

    private boolean before(ReviewExecutionAttempt first, ReviewExecutionAttempt second) {
        return first != null && second != null && first.getAttemptNo() != null && second.getAttemptNo() != null
            && first.getAttemptNo() < second.getAttemptNo();
    }

    private boolean before(ReviewFinding finding, ReviewExecutionAttempt candidate, Map<Long, Integer> numbers) {
        Integer number = finding.getAttemptId() == null ? null : numbers.get(finding.getAttemptId());
        return number != null && candidate.getAttemptNo() != null && number < candidate.getAttemptNo();
    }

    private List<ReviewFinding> findingsFor(List<ReviewFinding> findings, Long attemptId) {
        return findings.stream().filter(finding -> Objects.equals(attemptId, finding.getAttemptId()))
            .filter(finding -> "FINDING".equalsIgnoreCase(finding.getCategory()))
            .sorted(Comparator.comparing(ReviewFinding::getId, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    private Map<Long, Integer> attemptNumbers(List<ReviewExecutionAttempt> attempts, ReviewExecutionAttempt baseline,
        ReviewExecutionAttempt candidate) {
        Map<Long, Integer> numbers = new HashMap<>();
        attempts.forEach(attempt -> { if (attempt.getId() != null && attempt.getAttemptNo() != null)
            numbers.put(attempt.getId(), attempt.getAttemptNo()); });
        if (baseline != null && baseline.getId() != null) numbers.put(baseline.getId(), baseline.getAttemptNo());
        if (candidate.getId() != null) numbers.put(candidate.getId(), candidate.getAttemptNo());
        return numbers;
    }

    private String ensureFingerprint(Long taskId, ReviewFinding finding) {
        if (!StringUtils.hasText(finding.getFindingFingerprint()))
            finding.setFindingFingerprint(ReviewFindingIdentity.fingerprint(taskId, finding));
        return finding.getFindingFingerprint();
    }

    private ReviewTask requireTask(Long taskId) {
        var task = taskMapper.selectById(taskId);
        if (task == null) throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        return task;
    }

    private ReviewExecutionAttempt requireAttempt(Long taskId, Long attemptId) {
        ReviewExecutionAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || !Objects.equals(taskId, attempt.getTaskId()))
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review execution attempt not found: " + attemptId);
        return attempt;
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid finding comparison page");
    }

    private static boolean successful(ReviewExecutionAttempt attempt) {
        return attempt != null && ("COMPLETED".equalsIgnoreCase(attempt.getStatus())
            || "PARTIAL".equalsIgnoreCase(attempt.getStatus()));
    }

    private ReviewFindingComparisonSummaryDto summary(List<ComparisonEntry> entries) {
        return new ReviewFindingComparisonSummaryDto(count(entries, "NEW"), count(entries, "PERSISTING"),
            count(entries, "RESOLVED"), count(entries, "REGRESSED"), count(entries, "UNMATCHED"), entries.size());
    }

    private long count(List<ComparisonEntry> entries, String status) {
        return entries.stream().filter(entry -> status.equals(entry.status())).count();
    }

    private int statusOrder(String status) {
        return switch (status) {
            case "NEW" -> 0;
            case "REGRESSED" -> 1;
            case "PERSISTING" -> 2;
            case "UNMATCHED" -> 3;
            case "RESOLVED" -> 4;
            default -> 5;
        };
    }

    private String safe(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private record ComparisonEntry(ReviewFinding finding, Long baselineFindingId, String status,
        BigDecimal confidence, String reason) {
        ReviewFindingComparisonDto toDto() {
            return new ReviewFindingComparisonDto(finding.getId(), finding.getAttemptId(), baselineFindingId, status,
                finding.getFindingFingerprint(), confidence, reason, ReviewFindingIdentity.VERSION, finding.getCategory(),
                finding.getSeverity(), finding.getSource(), finding.getRuleId(), finding.getFilePath(),
                finding.getLineNumber(), finding.getMessage(), finding.getRecommendation(), finding.getIsBlocking(),
                FindingFeedbackStatus.fromFinding(finding).dtoCode());
        }
    }
}
