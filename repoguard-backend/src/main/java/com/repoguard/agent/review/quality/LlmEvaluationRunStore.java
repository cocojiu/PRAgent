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
        LocalDateTime finishedAt
    ) {
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
                finishedAt
            );
        }

        boolean terminal() {
            return "COMPLETE".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
        }
    }
}
