package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.ReviewQuery;
import org.junit.jupiter.api.Test;

class ReviewTaskListQueryBuilderTest {

    private final ReviewTaskListQueryBuilder builder = new ReviewTaskListQueryBuilder();

    @Test
    void buildsNormalizedFiltersAndNumericKeywordCondition() {
        var query = new ReviewQuery(
            1,
            20,
            " repo-a ",
            " failed ",
            " high ",
            " manual_input ",
            " github_webhook ",
            "42"
        );

        var criteria = builder.normalize(query);
        var wrapper = builder.build(query);

        assertThat(criteria.repository()).isEqualTo("repo-a");
        assertThat(criteria.status()).isEqualTo("FAILED");
        assertThat(criteria.riskLevel()).isEqualTo("HIGH");
        assertThat(criteria.source()).isEqualTo("MANUAL_INPUT");
        assertThat(criteria.triggerSource()).isEqualTo("GITHUB_WEBHOOK");
        assertThat(criteria.keyword()).isEqualTo("42");
        assertThat(criteria.prNumber()).isEqualTo(42);
        assertThat(wrapper.getSqlSegment()).contains("ORDER BY");
    }

    @Test
    void skipsBlankFiltersAndDoesNotAddPrNumberConditionForTextKeyword() {
        var criteria = builder.normalize(new ReviewQuery(
            1,
            20,
            " ",
            null,
            "",
            null,
            " ",
            "security"
        ));

        assertThat(criteria.repository()).isNull();
        assertThat(criteria.status()).isNull();
        assertThat(criteria.riskLevel()).isNull();
        assertThat(criteria.source()).isNull();
        assertThat(criteria.triggerSource()).isNull();
        assertThat(criteria.keyword()).isEqualTo("security");
        assertThat(criteria.prNumber()).isNull();
    }
}
