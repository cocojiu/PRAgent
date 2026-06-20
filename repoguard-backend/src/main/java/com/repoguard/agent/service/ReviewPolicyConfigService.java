package com.repoguard.agent.service;

import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;

public interface ReviewPolicyConfigService {

    ReviewPolicyConfigDto getReviewPolicy();

    ReviewPolicyConfigDto updateReviewPolicy(ReviewPolicyConfigRequest request);
}
