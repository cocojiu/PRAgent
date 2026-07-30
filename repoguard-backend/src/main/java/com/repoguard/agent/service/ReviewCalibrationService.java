package com.repoguard.agent.service;

import com.repoguard.agent.dto.ReviewCalibrationQueueDto;

public interface ReviewCalibrationService {

    ReviewCalibrationQueueDto getQueue(String ruleId, int limit, boolean includeIgnored);
}
