package com.repoguard.agent.dto;

public record ReviewStrategyPolicyDto(
    long snapshotId,
    long strategyVersion,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String verifierVersion,
    String aggregationVersion,
    String enforcementMode,
    boolean replayVerified,
    boolean active,
    String changeType,
    Long sourceSnapshotId,
    String createdAt,
    ReviewRuleQualityGateDto qualityGate
) {
}
