package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReviewRuleRegistryTest {

    @Test
    void rejectsDuplicateDetectorIdsAcrossLineAndPullRequestRules() {
        assertThatThrownBy(() -> new ReviewRuleRegistry(
            List.of(lineRule("rg-custom-001", 20)),
            List.of(pullRequestRule("RG-CUSTOM-001", 10))
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate review rule detector id RG-CUSTOM-001");
    }

    @Test
    void exposesImmutableNormalizedIdsAndStableRuleOrder() {
        ReviewRuleRegistry registry = new ReviewRuleRegistry(
            List.of(lineRule("RG-B", 20), lineRule("RG-A", 10)),
            List.of()
        );

        assertThat(registry.ruleIds()).containsExactlyInAnyOrder("RG-A", "RG-B");
        assertThat(registry.lineRules()).extracting(ReviewRule::id).containsExactly("RG-A", "RG-B");
        assertThatThrownBy(() -> registry.ruleIds().add("RG-C"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private ReviewRule lineRule(String id, int order) {
        return new ReviewRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public Optional<RuleMatch> evaluate(ReviewRuleLineContext context) {
                return Optional.empty();
            }
        };
    }

    private PullRequestReviewRule pullRequestRule(String id, int order) {
        return new PullRequestReviewRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public List<RuleMatch> evaluate(
                PullRequestDiff diff,
                Map<String, ReviewRuleSettings> configuredRules
            ) {
                return List.of();
            }
        };
    }
}
