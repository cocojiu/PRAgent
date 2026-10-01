package com.repoguard.agent.scanner;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.review.ReviewFindingIdentity;
import com.repoguard.agent.scanner.SarifReportParser.ParsedResult;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounds SQL rows and estimated wire bytes while keeping every batch in the caller's transaction. */
final class SarifFindingBatchWriter {
    private SarifFindingBatchWriter() { }
    static void insert(ReviewFindingMapper mapper, Long taskId, Long attemptId, Long batchId, List<ParsedResult> results) {
        if (results.isEmpty()) return;
        long budget = Math.min(256 * 1024L, Math.max(0, mapper.selectSarifWritePacketBytes() / 2 - 8192));
        List<ReviewFinding> batch = new ArrayList<>(200);
        long bytes = 0;
        for (ParsedResult parsed : results) {
            ReviewFinding finding = toEntity(taskId, attemptId, batchId, parsed);
            // Fixed columns/parameter overhead plus doubled UTF-8 text allow for SQL escaping.
            long rowBytes = 1024L + 2L * (2L * utf8(parsed.message()) + 2L * utf8(parsed.help())
                + utf8(parsed.filePath()) + 2L * utf8(parsed.ruleId()) + 64);
            if (rowBytes > budget) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF finding exceeds the database packet budget");
            }
            if (!batch.isEmpty() && (batch.size() == 200 || bytes + rowBytes > budget)) {
                write(mapper, batch); batch.clear(); bytes = 0;
            }
            batch.add(finding); bytes += rowBytes;
        }
        if (!batch.isEmpty()) write(mapper, batch);
    }
    private static int utf8(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }
    private static void write(ReviewFindingMapper mapper, List<ReviewFinding> batch) {
        if (mapper.insertSarifFindings(TenantContext.currentTenantIdOrDefault(), List.copyOf(batch)) != batch.size()) {
            throw new IllegalStateException("SARIF finding batch was not completely inserted");
        }
    }
    private static ReviewFinding toEntity(Long taskId, Long attemptId, Long batchId, ParsedResult parsed) {
        ReviewFinding finding = new ReviewFinding();
        finding.setTaskId(taskId);
        finding.setAttemptId(attemptId);
        finding.setSourceBatchId(batchId);
        finding.setCurrentAttempt(true);
        finding.setCategory("FINDING");
        finding.setSeverity(parsed.severity());
        finding.setSource("SARIF");
        finding.setRuleId(parsed.ruleId());
        finding.setFilePath(parsed.filePath());
        finding.setLineNumber(parsed.lineNumber());
        finding.setMessage(parsed.message());
        finding.setRecommendation(parsed.help());
        finding.setConfidence("HIGH");
        finding.setEvidence(parsed.message());
        finding.setImpact("third_party_scan");
        finding.setFixExample(parsed.help());
        finding.setIsBlocking(false);
        finding.setEnforcementMode("COMMENT");
        finding.setPolicyReason("sarif_import");
        finding.setIssueType(parsed.ruleId());
        finding.setRelatedFiles("");
        finding.setBlockingCandidate(false);
        finding.setVerificationStatus("NOT_REQUIRED");
        finding.setDetectorVersion("sarif-2.1.0");
        finding.setRuleConfigVersion(1L);
        finding.setPromptVersion("not-applicable");
        finding.setContextVersion("not-applicable");
        finding.setSchemaVersion("sarif-2.1.0");
        finding.setVerifierVersion("not-applicable");
        finding.setAggregationVersion("sarif-import-v1");
        finding.setPolicyVersion(1L);
        finding.setOriginalSeverity(parsed.severity());
        finding.setOriginalConfidence("HIGH");
        finding.setOriginalIsBlocking(false);
        finding.setDowngradeReason("");
        finding.setBlockReason("");
        finding.setAnchorType("ADDED_LINE");
        finding.setReviewDimension("SECURITY");
        finding.setFindingFingerprint(ReviewFindingIdentity.fingerprint(taskId, finding));
        finding.setComparisonStatus("UNMATCHED");
        finding.setComparisonConfidence(BigDecimal.ZERO);
        finding.setComparisonReason("PENDING_COMPARISON");
        finding.setComparisonVersion(ReviewFindingIdentity.VERSION);
        return finding;
    }
}
