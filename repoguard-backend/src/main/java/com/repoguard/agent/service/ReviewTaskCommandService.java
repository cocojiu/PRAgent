package com.repoguard.agent.service;

import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.ReviewRetryResponse;

public interface ReviewTaskCommandService {

    ManualReviewResponse triggerManualReview(ManualReviewRequest request);

    ReviewRetryResponse retryReview(Long id);
}
