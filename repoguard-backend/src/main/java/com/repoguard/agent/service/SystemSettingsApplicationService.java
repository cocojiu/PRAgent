package com.repoguard.agent.service;

import com.repoguard.agent.dto.SystemSettingsDto;
import com.repoguard.agent.dto.SystemSettingsRequest;

public interface SystemSettingsApplicationService {

    SystemSettingsDto getSystemSettings();

    SystemSettingsDto updateSystemSettings(SystemSettingsRequest request);
}
