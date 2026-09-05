package com.repoguard.agent.review.quality;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.LlmParseStatus;
import com.repoguard.agent.review.LlmPullRequestReviewer;
import com.repoguard.agent.review.PullRequestChangedFile;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Runs one external evaluation sample through the production review pipeline and reduces the
 * result to aggregate-only labels. No source, prompt or provider response is returned or stored.
 */
@Component
public class LlmEvaluationPreviewRunner {

    private final LlmPullRequestReviewer reviewer;

    public LlmEvaluationPreviewRunner(LlmPullRequestReviewer reviewer) {
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer");
    }

    public LlmEvaluationObservation run(
        LlmEvaluationDatasetLoader.EvaluationCase sample,
        String provider,
        String model,
        ReviewDeadline deadline
    ) {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(deadline, "deadline");
        deadline.requireRemaining("evaluation_case");

        ReviewTask task = new ReviewTask();
        task.setPrNumber(sample.prNumber());
        task.setTitle(sample.title());
        task.setOrganization(sample.organization());
        task.setRepository(sample.repository());
        task.setCommitSha(sample.headSha());
        task.setBranchName(sample.branch());
        task.setSource("EVALUATION");
        task.setTriggerSource("EVALUATION");
        task.setStatus("EVALUATION");

        List<PullRequestChangedFile> files = sample.files().stream()
            .map(file -> new PullRequestChangedFile(
                file.filename(),
                file.status(),
                file.additions(),
                file.deletions(),
                file.patch()
            ))
            .toList();
        PullRequestDiff diff = new PullRequestDiff(
            sample.organization(),
            sample.repository(),
            sample.prNumber(),
            sample.headSha(),
            files
        );
        ReviewResult result = reviewer.reviewForEvaluation(task, diff, deadline, provider, model);
        return observation(sample, result);
    }

    private LlmEvaluationObservation observation(
        LlmEvaluationDatasetLoader.EvaluationCase sample,
        ReviewResult result
    ) {
        List<ReviewFindingResult> findings = result.findings() == null ? List.of() : result.findings();
        boolean predictedFinding = !findings.isEmpty();
        String predictedSeverity = findings.stream()
            .map(ReviewFindingResult::severity)
            .filter(Objects::nonNull)
            .max(Comparator.comparingInt(this::severityRank))
            .orElse("NONE");
        boolean anchorValid = !predictedFinding || findings.stream().anyMatch(
            finding -> locationKey(finding).equalsIgnoreCase(normalize(sample.expectedLocationKey()))
        );
        long ruleFindings = findings.stream().filter(finding -> "RULE".equalsIgnoreCase(finding.source())).count();
        long llmFindings = findings.stream().filter(finding -> "LLM".equalsIgnoreCase(finding.source())).count();
        long verifiedFindings = findings.stream().filter(
            finding -> "VERIFIED".equalsIgnoreCase(finding.verificationStatus())
        ).count();
        Integer duration = result.llmDurationMs();
        Integer totalTokens = result.llmTotalTokens();
        BigDecimal estimatedCost = result.llmEstimatedCost();
        boolean parseSucceeded = LlmParseStatus.PARSED.is(result.llmParseStatus());
        boolean commentAttempted = sample.commentPublished() != null
            || sample.commentFixed() != null
            || sample.commentIgnored() != null;
        LlmEvaluationObservation.EvaluationSplit split = split(sample.split());
        int changedLines = sample.files().stream()
            .mapToInt(file -> Math.max(0, file.additions()) + Math.max(0, file.deletions()))
            .sum();
        LlmEvaluationSampleContext context = new LlmEvaluationSampleContext(
            sample.language(),
            sample.files().size(),
            changedLines,
            sample.fileTypeGroup(),
            contextLocationKey(sample.expectedLocationKey())
        );
        return new LlmEvaluationObservation(
            sample.caseId(),
            sample.fileTypeGroup(),
            sample.expectedFinding(),
            sample.expectedSeverity(),
            predictedFinding,
            predictedSeverity,
            anchorValid,
            predictionKey(findings),
            parseSucceeded,
            duration == null ? 0L : duration,
            totalTokens == null ? 0L : totalTokens,
            estimatedCost,
            sample.usefulComment(),
            commentAttempted,
            sample.commentPublished(),
            sample.commentFixed(),
            sample.commentIgnored(),
            ruleFindings,
            llmFindings,
            verifiedFindings,
            split,
            sample.sourceRepositoryKey(),
            context,
            failureCategories(result)
        );
    }

    private String failureCategories(ReviewResult result) {
        String detail = result.statusDetail();
        String marker = "llmFailureCategories=";
        if (detail != null) {
            int start = detail.indexOf(marker);
            if (start >= 0) {
                String value = detail.substring(start + marker.length());
                int end = value.indexOf(';');
                return end < 0 ? value : value.substring(0, end);
            }
        }
        return "fallback".equalsIgnoreCase(result.llmStatus()) ? "llm_fallback" : "";
    }

    private LlmEvaluationObservation.EvaluationSplit split(String value) {
        if (value == null || value.isBlank()) {
            return LlmEvaluationObservation.EvaluationSplit.UNSPECIFIED;
        }
        try {
            return LlmEvaluationObservation.EvaluationSplit.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return LlmEvaluationObservation.EvaluationSplit.UNSPECIFIED;
        }
    }

    private String locationKey(ReviewFindingResult finding) {
        String path = finding.filePath() == null ? "" : finding.filePath().trim();
        return path + ":" + (finding.lineNumber() == null ? "" : finding.lineNumber());
    }

    private String predictionKey(List<ReviewFindingResult> findings) {
        if (findings.isEmpty()) {
            return "";
        }
        String value = findings.stream()
            .map(finding -> normalize(finding.ruleId()) + "|"
                + normalize(finding.filePath()) + "|"
                + (finding.lineNumber() == null ? "" : finding.lineNumber()) + "|"
                + normalize(finding.severity()))
            .sorted()
            .reduce((first, second) -> first + "\n" + second)
            .orElse("");
        return sha256(value);
    }

    private String contextLocationKey(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? "" : sha256(normalized);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for evaluation prediction keys", ex);
        }
    }

    private int severityRank(String severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity.trim().toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            default -> 1;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
