package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewRuleProvider;
import java.util.List;

final class ReviewRuleTestFixtures {

    private ReviewRuleTestFixtures() {
    }

    static RuleBasedPullRequestReviewer defaultReviewer(ReviewRuleProvider reviewRuleProvider) {
        ReviewFindingFactory findingFactory = new ReviewFindingFactory();
        return new RuleBasedPullRequestReviewer(
            reviewRuleProvider,
            findingFactory,
            defaultLineRules(findingFactory)
        );
    }

    static List<ReviewRule> defaultLineRules(ReviewFindingFactory findingFactory) {
        return List.of(
            new BroadExceptionCatchRule(findingFactory),
            new StandardOutputLoggingRule(findingFactory),
            new FixedSleepRule(findingFactory),
            new TodoCommentRule(findingFactory),
            new SensitiveLiteralRule(findingFactory),
            new SensitiveLoggingRule(findingFactory),
            new TaskStatusStringRule(findingFactory),
            new RabbitMessagePublishRule(findingFactory),
            new RawExternalCallRule(findingFactory),
            new DestructiveMigrationRule(findingFactory),
            new RequiredColumnWithoutDefaultRule(findingFactory),
            new GithubCommentDirectPublishRule(findingFactory)
        );
    }
}
