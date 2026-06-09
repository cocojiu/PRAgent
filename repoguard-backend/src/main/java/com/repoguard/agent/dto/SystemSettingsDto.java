package com.repoguard.agent.dto;

import java.util.List;

public record SystemSettingsDto(
    BaseSettingsDto base,
    ReviewPolicySettingsDto policy,
    NotificationSettingsDto notification,
    SecuritySettingsDto security,
    List<SettingLogDto> logs
) {
}
