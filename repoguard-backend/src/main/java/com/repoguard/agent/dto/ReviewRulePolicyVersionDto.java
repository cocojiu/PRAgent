package com.repoguard.agent.dto;

public record ReviewRulePolicyVersionDto(
    long policyVersion,
    long configVersion,
    String detectorVersion,
    String severity,
    String status,
    String confidence,
    String enforcementMode,
    String changeType,
    Long sourcePolicyVersion,
    String createdAt,
    boolean active
) {
}
