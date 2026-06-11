package com.repoguard.agent.dto;

public record ConnectionTestResultDto(
    Boolean success,
    String status,
    String message,
    String checkedAt,
    String testedConfigSource,
    Boolean runtimeHealthy,
    Boolean savedConfigHealthy,
    Boolean mismatch,
    String runtimeConnectionStatus,
    String savedConfigStatus
) {
    public ConnectionTestResultDto(Boolean success, String status, String message, String checkedAt) {
        this(success, status, message, checkedAt, null, null, null, null, null, null);
    }
}
