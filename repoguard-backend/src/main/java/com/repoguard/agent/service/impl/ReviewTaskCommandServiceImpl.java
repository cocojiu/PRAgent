package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.HumanReviewResponse;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.service.ReviewTaskCommandService;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewTaskCommandServiceImpl implements ReviewTaskCommandService {

    private final HumanReviewCommandService humanReviewCommandService;
    private final ReviewTaskRetryService reviewTaskRetryService;
    private final ManualReviewCreationService manualReviewCreationService;

    public ReviewTaskCommandServiceImpl(
        HumanReviewCommandService humanReviewCommandService,
        ReviewTaskRetryService reviewTaskRetryService,
        ManualReviewCreationService manualReviewCreationService
    ) {
        this.humanReviewCommandService = Objects.requireNonNull(humanReviewCommandService, "humanReviewCommandService");
        this.reviewTaskRetryService = Objects.requireNonNull(reviewTaskRetryService, "reviewTaskRetryService");
        this.manualReviewCreationService = Objects.requireNonNull(manualReviewCreationService, "manualReviewCreationService");
    }

    @Override
    public ManualReviewResponse triggerManualReview(ManualReviewRequest request) {
        return manualReviewCreationService.triggerManualReview(request);
    }

    @Override
    @Transactional
    public HumanReviewResponse submitHumanReview(Long id, HumanReviewRequest request, String operator) {
        return humanReviewCommandService.submit(id, request, operator);
    }

    @Override
    @Transactional
    public ReviewRetryResponse retryReview(Long id) {
        return reviewTaskRetryService.retry(id);
    }

}
