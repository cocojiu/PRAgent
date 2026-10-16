package com.repoguard.agent.service;

import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.FindingFeedbackResponse;
import java.time.LocalDateTime;

public interface FindingFeedbackService {

    FindingFeedbackResponse updateFindingFeedback(Long taskId, Long findingId, FindingFeedbackRequest request, String operator);

    /** Apply a verified remote decision using its occurrence time, independently of queue delay. */
    FindingFeedbackResponse applyVerifiedGithubFeedback(Long taskId, Long findingId, FindingFeedbackRequest request,
        String actor, LocalDateTime occurredAt);
}
