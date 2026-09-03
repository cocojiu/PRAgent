package com.repoguard.agent.dto;

import com.repoguard.agent.review.RepositoryPolicyDocument;
import com.repoguard.agent.review.RepositoryPolicyEvaluationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record RepositoryPolicyPreviewResponse(
    RepositoryPolicyDocument basePolicy,
    RepositoryPolicyDocument headPolicy,
    Map<String, RepositoryPolicyEvaluationService.RuleDecision> rules,
    Boolean effectiveLlmEnabled,
    Integer effectiveTokenBudget,
    BigDecimal effectiveCostBudget,
    String commentMode,
    String checkMode,
    List<String> warnings
) {
}
