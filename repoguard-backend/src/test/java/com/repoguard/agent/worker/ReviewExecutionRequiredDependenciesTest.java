package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.review.RiskLevelRanker;
import org.junit.jupiter.api.Test;

class ReviewExecutionRequiredDependenciesTest {

    @Test
    void claimServiceRequiresStateMachineDependency() {
        ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);

        assertThatThrownBy(() -> new ReviewTaskClaimService(reviewTaskMapper, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }

    @Test
    void completionApplierRequiresStateMachineDependency() {
        assertThatThrownBy(() -> new ReviewTaskCompletionApplier(null, new RiskLevelRanker()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }

    @Test
    void completionApplierRequiresRiskLevelRankerDependency() {
        assertThatThrownBy(() -> new ReviewTaskCompletionApplier(new ReviewTaskStateMachine(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("riskLevelRanker");
    }

    @Test
    void findingReplacementServiceRequiresDeduplicatorDependency() {
        ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);

        assertThatThrownBy(() -> new ReviewFindingReplacementService(reviewFindingMapper, null, new ReviewFindingEntityMapper()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("findingDeduplicator");
    }

    @Test
    void findingReplacementServiceRequiresEntityMapperDependency() {
        ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);

        assertThatThrownBy(() -> new ReviewFindingReplacementService(
            reviewFindingMapper,
            new ReviewFindingDeduplicator(
                new ReviewFindingDeduplicationKeyResolver(),
                new ReviewFindingMergeService(new RiskLevelRanker())
            ),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("findingEntityMapper");
    }

    @Test
    void findingDeduplicatorRequiresKeyResolverDependency() {
        assertThatThrownBy(() -> new ReviewFindingDeduplicator(null, new ReviewFindingMergeService(new RiskLevelRanker())))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("keyResolver");
    }

    @Test
    void findingDeduplicatorRequiresMergeServiceDependency() {
        assertThatThrownBy(() -> new ReviewFindingDeduplicator(new ReviewFindingDeduplicationKeyResolver(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("mergeService");
    }

    @Test
    void findingMergeServiceRequiresRiskLevelRankerDependency() {
        assertThatThrownBy(() -> new ReviewFindingMergeService(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("riskLevelRanker");
    }

    @Test
    void failureHandlerRequiresCompletionApplierDependency() {
        ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);

        assertThatThrownBy(() -> new ReviewExecutionFailureHandler(
            reviewTaskMapper,
            null,
            null,
            null,
            null,
            null,
            new ReviewExecutionClock()
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("completionApplier");
    }

    @Test
    void requiredDependenciesAllowExplicitWiring() {
        ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
        ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
        ReviewTaskStateMachine stateMachine = new ReviewTaskStateMachine();
        RiskLevelRanker riskLevelRanker = new RiskLevelRanker();

        new ReviewTaskClaimService(reviewTaskMapper, stateMachine);
        ReviewTaskCompletionApplier completionApplier = new ReviewTaskCompletionApplier(stateMachine, riskLevelRanker);
        new ReviewFindingReplacementService(
            reviewFindingMapper,
            new ReviewFindingDeduplicator(
                new ReviewFindingDeduplicationKeyResolver(),
                new ReviewFindingMergeService(riskLevelRanker)
            ),
            new ReviewFindingEntityMapper()
        );
        new ReviewExecutionFailureHandler(
            reviewTaskMapper,
            null,
            completionApplier,
            null,
            null,
            null,
            new ReviewExecutionClock()
        );
    }
}
