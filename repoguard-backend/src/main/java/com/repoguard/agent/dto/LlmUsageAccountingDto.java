package com.repoguard.agent.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Transport contracts for bounded diagnostics and explicit, audited accounting reconciliation. */
public final class LlmUsageAccountingDto {
    private LlmUsageAccountingDto() { }

    public record PendingUsage(String callKey, LocalDate month, Long taskId, Long attemptId,
        String operation, String provider, String modelName, String status, long accountedTokens,
        BigDecimal accountedCost, OffsetDateTime startedAt) { }

    public record ReconciliationRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9:._-]{1,128}$") String callKey,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$") String idempotencyKey,
        @Min(0) @Max(9_007_199_254_740_991L) long accountedTokens,
        @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 8) BigDecimal accountedCost,
        @NotBlank @Size(max = 512) String evidenceReference,
        @NotBlank @Size(max = 512) String reason,
        @AssertTrue(message = "Only final provider evidence can release an uncertain reservation") boolean finalEvidence
    ) { }

    public record ReconciledUsage(String idempotencyKey, String callKey, long accountedTokens,
        BigDecimal accountedCost, String operator, String evidenceReference, String reason,
        OffsetDateTime reconciledAt) { }
}
