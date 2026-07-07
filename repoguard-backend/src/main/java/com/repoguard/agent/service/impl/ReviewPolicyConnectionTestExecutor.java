package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewPolicyConnectionTestExecutor {

    private static final long DEFAULT_REVIEW_POLICY_ID = 1L;

    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final ConnectionTestConfigFactory configFactory;

    ReviewPolicyConnectionTestExecutor(
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        ConnectionTestConfigFactory configFactory
    ) {
        this.reviewPolicyConfigMapper = Objects.requireNonNull(reviewPolicyConfigMapper, "reviewPolicyConfigMapper");
        this.configFactory = Objects.requireNonNull(configFactory, "configFactory");
    }

    ConnectionTestResultDto test(
        ReviewPolicyConfigRequest configRequest,
        LlmReviewPolicyConnectionTestRunner runner
    ) {
        Objects.requireNonNull(runner, "runner");

        ReviewPolicyConfig savedConfig = reviewPolicyConfigMapper.selectById(DEFAULT_REVIEW_POLICY_ID);
        ReviewPolicyConfig config = configRequest == null
            ? savedConfig
            : configFactory.reviewPolicyForTest(configRequest, savedConfig);
        return runner.run(config);
    }
}
