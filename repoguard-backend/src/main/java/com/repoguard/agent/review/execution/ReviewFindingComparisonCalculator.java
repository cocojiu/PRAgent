package com.repoguard.agent.review.execution;

import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.review.FindingFeedbackStatus;
import com.repoguard.agent.review.ReviewFindingIdentity;
import com.repoguard.agent.dto.ReviewFindingComparisonDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/** Pure comparison. No persistence, feedback changes, or entity mutation. */
final class ReviewFindingComparisonCalculator {
    private ReviewFindingComparisonCalculator() { }

    static List<Entry> standalone(Long taskId, List<ReviewFinding> current, String reason, String status) {
        return current.stream().map(finding -> entry(taskId, finding, null, status, reason)).toList();
    }

    static List<Entry> compare(Long taskId, List<ReviewFinding> previous, List<ReviewFinding> current,
        List<ReviewFinding> history) {
        Map<String, ReviewFinding> previousByFingerprint = new LinkedHashMap<>();
        previous.forEach(finding -> previousByFingerprint.putIfAbsent(fingerprint(taskId, finding), finding));
        Map<String, ReviewFinding> resolvedHistory = new LinkedHashMap<>();
        history.forEach(finding -> resolvedHistory.putIfAbsent(fingerprint(taskId, finding), finding));
        Set<String> locationKeys = new HashSet<>();
        previous.forEach(finding -> locationKeys.add(ReviewFindingIdentity.locationIndependentKey(taskId, finding)));
        Set<String> seen = new HashSet<>();
        Set<Long> matched = new HashSet<>();
        List<Entry> entries = new ArrayList<>();
        for (ReviewFinding finding : current) {
            String fingerprint = fingerprint(taskId, finding);
            Entry result;
            if (!StringUtils.hasText(fingerprint)) result = entry(taskId, finding, null, "UNMATCHED", "MISSING_FINGERPRINT");
            else if (!seen.add(fingerprint)) result = entry(taskId, finding, null, "UNMATCHED", "DUPLICATE_FINGERPRINT");
            else {
                ReviewFinding old = previousByFingerprint.get(fingerprint);
                if (old != null) {
                    matched.add(old.getId());
                    // A historical row's mutable comparison_status can refer to a later candidate.
                    result = entry(taskId, finding, old, "PERSISTING", "STABLE_FINGERPRINT_MATCH");
                } else if ((old = resolvedHistory.get(fingerprint)) != null) {
                    result = entry(taskId, finding, old, "REGRESSED", "REAPPEARED_AFTER_RESOLUTION");
                } else if (locationKeys.contains(ReviewFindingIdentity.locationIndependentKey(taskId, finding))) {
                    result = entry(taskId, finding, null, "UNMATCHED", "LOCATION_CHANGED_OR_CROSS_FILE");
                } else result = entry(taskId, finding, null, "NEW", "NO_MATCHING_PREVIOUS_FINGERPRINT");
            }
            entries.add(result);
        }
        for (ReviewFinding old : previous) {
            if (old.getId() != null && !matched.contains(old.getId())) {
                entries.add(new Entry(old, fingerprint(taskId, old), old.getPreviousFindingId(), "RESOLVED",
                    BigDecimal.ONE, "NOT_PRESENT_IN_CANDIDATE"));
            }
        }
        return entries;
    }

    static String fingerprint(Long taskId, ReviewFinding finding) {
        return StringUtils.hasText(finding.getFindingFingerprint()) ? finding.getFindingFingerprint()
            : ReviewFindingIdentity.fingerprint(taskId, finding);
    }

    private static Entry entry(Long taskId, ReviewFinding finding, ReviewFinding old, String status, String reason) {
        return new Entry(finding, fingerprint(taskId, finding), old == null ? null : old.getId(), status,
            "UNMATCHED".equals(status) ? BigDecimal.ZERO : BigDecimal.ONE, reason);
    }

    record Entry(ReviewFinding finding, String fingerprint, Long baselineFindingId, String status,
        BigDecimal confidence, String reason) {
        ReviewFindingComparisonDto toDto() {
            return new ReviewFindingComparisonDto(finding.getId(), finding.getAttemptId(), baselineFindingId, status,
                fingerprint, confidence, reason, ReviewFindingIdentity.VERSION, finding.getCategory(),
                finding.getSeverity(), finding.getSource(), finding.getRuleId(), finding.getFilePath(),
                finding.getLineNumber(), finding.getMessage(), finding.getRecommendation(), finding.getIsBlocking(),
                FindingFeedbackStatus.fromFinding(finding).dtoCode());
        }
    }
}
