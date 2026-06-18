package com.repoguard.agent.service;

import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRulesResponse;

public interface ReviewRuleConfigService {

    ReviewRulesResponse getReviewRules();

    ReviewRuleConfigDto createReviewRule(ReviewRuleConfigRequest request);

    ReviewRuleConfigDto updateReviewRule(String id, ReviewRuleConfigRequest request);

    ReviewRuleConfigDto updateReviewRuleStatus(String id, String status);
}
