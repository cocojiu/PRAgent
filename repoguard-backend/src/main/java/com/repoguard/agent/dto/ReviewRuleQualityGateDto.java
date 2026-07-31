package com.repoguard.agent.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReviewRuleQualityGateDto(
    long labeledSamples,
    long labeledHighRiskSamples,
    BigDecimal precision,
    BigDecimal falsePositiveRate,
    BigDecimal anchorRate,
    BigDecimal duplicateRate,
    boolean commentEligible,
    boolean blockEligible,
    String status,
    List<String> blockers
) {
}
