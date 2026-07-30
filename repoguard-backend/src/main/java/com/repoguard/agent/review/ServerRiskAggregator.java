package com.repoguard.agent.review;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ServerRiskAggregator {

    public String aggregate(List<ReviewFindingResult> findings) {
        List<ReviewFindingResult> effective = findings == null
            ? List.of()
            : findings.stream().filter(this::contributesToRisk).toList();
        if (effective.stream().anyMatch(this::isBlockingCritical)) {
            return "CRITICAL";
        }
        if (effective.stream().anyMatch(this::isBlockingHigh) || independentHighEvidenceCount(effective) >= 2) {
            return "HIGH";
        }
        if (effective.stream().anyMatch(this::isHighCandidate)
            || effective.stream().anyMatch(finding -> severity(finding).equals("MEDIUM"))) {
            return "MEDIUM";
        }
        if (effective.stream().anyMatch(finding -> severity(finding).equals("LOW"))) {
            return "LOW";
        }
        return "INFO";
    }

    private boolean contributesToRisk(ReviewFindingResult finding) {
        return finding != null
            && !"OBSERVE".equalsIgnoreCase(finding.enforcementMode())
            && !"FALSE_POSITIVE".equalsIgnoreCase(finding.policyReason());
    }

    private boolean isBlockingCritical(ReviewFindingResult finding) {
        return finding.isBlocking()
            && "CRITICAL".equals(severity(finding))
            && "HIGH".equalsIgnoreCase(finding.confidence());
    }

    private boolean isBlockingHigh(ReviewFindingResult finding) {
        return finding.isBlocking()
            && "HIGH".equals(severity(finding))
            && "HIGH".equalsIgnoreCase(finding.confidence());
    }

    private boolean isHighCandidate(ReviewFindingResult finding) {
        return "HIGH".equals(severity(finding)) || "CRITICAL".equals(severity(finding));
    }

    private int independentHighEvidenceCount(List<ReviewFindingResult> findings) {
        Set<String> evidenceSources = new HashSet<>();
        for (ReviewFindingResult finding : findings) {
            if (!"HIGH".equals(severity(finding)) || !"HIGH".equalsIgnoreCase(finding.confidence())) {
                continue;
            }
            String ruleId = finding.ruleId() == null ? "" : finding.ruleId().trim();
            String source = finding.source() == null ? "UNKNOWN" : finding.source().trim().toUpperCase(Locale.ROOT);
            evidenceSources.add(source + ":" + ruleId);
        }
        return evidenceSources.size();
    }

    private String severity(ReviewFindingResult finding) {
        return finding.severity() == null ? "" : finding.severity().trim().toUpperCase(Locale.ROOT);
    }
}
