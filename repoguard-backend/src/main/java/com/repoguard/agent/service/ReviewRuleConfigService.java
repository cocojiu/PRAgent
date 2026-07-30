package com.repoguard.agent.service;

import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRulePolicyVersionDto;
import com.repoguard.agent.dto.ReviewRulesResponse;
import java.util.List;

public interface ReviewRuleConfigService {

    ReviewRulesResponse getReviewRules();

    ReviewRuleConfigDto createReviewRule(ReviewRuleConfigRequest request);

    ReviewRuleConfigDto updateReviewRule(String id, ReviewRuleConfigRequest request);

    ReviewRuleConfigDto updateReviewRuleStatus(String id, String status);

    List<ReviewRulePolicyVersionDto> getReviewRuleVersions(String id);

    ReviewRuleConfigDto rollbackReviewRule(String id, long policyVersion);
}
