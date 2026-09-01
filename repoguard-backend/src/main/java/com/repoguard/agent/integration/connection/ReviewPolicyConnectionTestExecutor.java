package com.repoguard.agent.integration.connection;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.tenancy.TenantContext;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewPolicyConnectionTestExecutor {

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

        ReviewPolicyConfig savedConfig = reviewPolicyConfigMapper.selectByTenantId(
            TenantContext.currentTenantIdOrDefault()
        );
        ReviewPolicyConfig config = configRequest == null
            ? savedConfig
            : configFactory.reviewPolicyForTest(configRequest, savedConfig);
        return runner.run(config);
    }
}
