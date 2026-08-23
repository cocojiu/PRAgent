package com.repoguard.agent.service;

import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.HumanReviewResponse;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.ReviewRetryResponse;
import java.time.LocalDateTime;

public interface ReviewTaskCommandService {

    ManualReviewResponse triggerManualReview(ManualReviewRequest request);

    ManualReviewResponse triggerWebhookReview(ManualReviewRequest request, LocalDateTime headUpdatedAt);

    HumanReviewResponse submitHumanReview(Long id, HumanReviewRequest request, String operator);

    ReviewRetryResponse retryReview(Long id);
}
