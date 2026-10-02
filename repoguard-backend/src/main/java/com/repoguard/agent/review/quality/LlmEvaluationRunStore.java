package com.repoguard.agent.review.quality;

import com.repoguard.agent.dto.LlmEvaluationRunDto;
import java.math.BigDecimal;
import java.time.LocalDateTime;

interface LlmEvaluationRunStore {

    StoredRun createOrGet(StoredRun candidate);

    StoredRun findByRunId(long tenantId, String runId);

    void save(StoredRun run);

    /** Fails nonterminal rows whose own configured execution deadline has expired. */
    int recoverInterrupted(LocalDateTime recoveredAt);

    record StoredRun(
        long tenantId,
        String runId,
        String runKey,
        String status,
        String dataDirectory,
        int maxConcurrency,
        long maxTokens,
        BigDecimal maxCost,
        int maxDurationSeconds,
        String operator,
        int totalSamples,
        int completedSamples,
        long totalTokens,
        BigDecimal totalCost,
        Long reportId,
        String failureCode,
        LocalDateTime submittedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LlmEvaluationRunDto.Diagnostics diagnostics,
        LocalDateTime payloadPurgedAt
    ) {
        StoredRun(long tenantId, String runId, String runKey, String status, String dataDirectory,
            int maxConcurrency, long maxTokens, BigDecimal maxCost, int maxDurationSeconds, String operator,
            int totalSamples, int completedSamples, long totalTokens, BigDecimal totalCost, Long reportId,
            String failureCode, LocalDateTime submittedAt, LocalDateTime startedAt, LocalDateTime finishedAt,
            LlmEvaluationRunDto.Diagnostics diagnostics) {
            this(tenantId, runId, runKey, status, dataDirectory, maxConcurrency, maxTokens, maxCost,
                maxDurationSeconds, operator, totalSamples, completedSamples, totalTokens, totalCost, reportId,
                failureCode, submittedAt, startedAt, finishedAt, diagnostics, null);
        }
        LlmEvaluationRunDto dto() {
            return new LlmEvaluationRunDto(
                runId,
                runKey,
                status,
                totalSamples,
                completedSamples,
                totalTokens,
                totalCost,
                reportId,
                failureCode,
                submittedAt,
                startedAt,
                finishedAt,
                diagnostics, payloadPurgedAt
            );
        }

        boolean terminal() {
            return "COMPLETE".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
        }
    }
}
