package com.repoguard.agent.dto;

public record ReviewCalibrationVersionDto(
    String ruleId,
    String ruleName,
    String detectorVersion,
    long ruleConfigVersion,
    long rulePolicyVersion,
    long strategySnapshotId,
    long strategyVersion,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String verifierVersion,
    String aggregationVersion,
    String ruleEnforcementMode,
    String strategyEnforcementMode,
    boolean replayVerified,
    String versionKey
) {
}
