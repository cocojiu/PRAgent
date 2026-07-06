package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.review.ReviewTaskDetailAssembler;
import org.junit.jupiter.api.Test;

class ReviewTaskQueryServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTaskDetailAssembler detailAssembler = new ReviewTaskDetailAssembler(
        new ReviewRiskProfileBuilder(),
        new PrReviewSummaryBuilder()
    );
    private final ReviewTaskDetailDataLoader detailDataLoader =
        org.mockito.Mockito.mock(ReviewTaskDetailDataLoader.class);
    private final ReviewTaskQueryItemLoader queryItemLoader =
        org.mockito.Mockito.mock(ReviewTaskQueryItemLoader.class);
    private final ReviewTaskStatusAssembler statusAssembler = new ReviewTaskStatusAssembler();
    private final ReviewTaskListQueryBuilder listQueryBuilder = new ReviewTaskListQueryBuilder();

    @Test
    void constructorRejectsMissingDetailDataLoader() {
        assertThatThrownBy(() -> new ReviewTaskQueryServiceImpl(
            reviewTaskMapper,
            detailAssembler,
            null,
            queryItemLoader,
            statusAssembler,
            listQueryBuilder
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("detailDataLoader");
    }

    @Test
    void constructorRejectsMissingQueryItemLoader() {
        assertThatThrownBy(() -> new ReviewTaskQueryServiceImpl(
            reviewTaskMapper,
            detailAssembler,
            detailDataLoader,
            null,
            statusAssembler,
            listQueryBuilder
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("queryItemLoader");
    }
}
