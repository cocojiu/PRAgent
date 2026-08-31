package com.repoguard.agent.dto;

import java.time.OffsetDateTime;

public record SettingLogDto(
    String time,
    OffsetDateTime occurredAt,
    String operator,
    String action,
    String status
) {
    public SettingLogDto(String time, String operator, String action, String status) {
        this(time, null, operator, action, status);
    }
}
