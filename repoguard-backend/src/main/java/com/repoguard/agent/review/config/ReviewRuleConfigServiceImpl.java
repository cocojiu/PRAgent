package com.repoguard.agent.review.config;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRulePolicyVersionDto;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.service.ReviewRuleConfigService;
import java.util.Objects;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ReviewRuleConfigServiceImpl implements ReviewRuleConfigService {

    private final ReviewRuleQueryService queryService;
    private final ReviewRuleCommandService commandService;
    private final ReviewRulePolicyHistoryService historyService;

    public ReviewRuleConfigServiceImpl(
        ReviewRuleQueryService queryService,
        ReviewRuleCommandService commandService,
        ReviewRulePolicyHistoryService historyService
    ) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.commandService = Objects.requireNonNull(commandService, "commandService");
        this.historyService = Objects.requireNonNull(historyService, "historyService");
    }

    @Override
    @Cacheable(cacheNames = CacheNames.REVIEW_RULES)
    public ReviewRulesResponse getReviewRules() {
        return queryService.getReviewRules();
    }

    @Override
    public ReviewRuleConfigDto createReviewRule(ReviewRuleConfigRequest request) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "Dynamic review rule creation is disabled; only registered built-in rules can be edited"
        );
    }

    @Override
    public ReviewRuleConfigDto updateReviewRule(
        String id,
        ReviewRuleConfigRequest request,
        long expectedPolicyVersion
    ) {
        return commandService.updateRule(id, request, expectedPolicyVersion);
    }

    @Override
    public ReviewRuleConfigDto updateReviewRuleStatus(
        String id,
        String status,
        long expectedPolicyVersion
    ) {
        return commandService.updateStatus(id, status, expectedPolicyVersion);
    }

    @Override
    public PageResponse<ReviewRulePolicyVersionDto> getReviewRuleVersions(
        String id,
        Long cursor,
        int pageSize
    ) {
        return historyService.getVersions(id, cursor, pageSize);
    }

    @Override
    public ReviewRuleConfigDto rollbackReviewRule(
        String id,
        long policyVersion,
        long expectedPolicyVersion
    ) {
        return commandService.rollback(id, policyVersion, expectedPolicyVersion);
    }
}
