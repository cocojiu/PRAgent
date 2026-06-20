package com.repoguard.agent.service;

import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.FindingFeedbackResponse;

public interface FindingFeedbackService {

    FindingFeedbackResponse updateFindingFeedback(Long taskId, Long findingId, FindingFeedbackRequest request, String operator);
}
