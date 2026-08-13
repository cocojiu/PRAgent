package com.repoguard.agent.service;

import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRulePolicyVersionDto;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.dto.PageResponse;

public interface ReviewRuleConfigService {

    ReviewRulesResponse getReviewRules();

    ReviewRuleConfigDto createReviewRule(ReviewRuleConfigRequest request);

    ReviewRuleConfigDto updateReviewRule(String id, ReviewRuleConfigRequest request, long expectedPolicyVersion);

    ReviewRuleConfigDto updateReviewRuleStatus(String id, String status, long expectedPolicyVersion);

    PageResponse<ReviewRulePolicyVersionDto> getReviewRuleVersions(String id, Long cursor, int pageSize);

    ReviewRuleConfigDto rollbackReviewRule(String id, long policyVersion, long expectedPolicyVersion);
}
