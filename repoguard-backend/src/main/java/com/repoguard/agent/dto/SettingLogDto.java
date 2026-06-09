package com.repoguard.agent.dto;

public record SettingLogDto(
    String time,
    String operator,
    String action,
    String status
) {
}
