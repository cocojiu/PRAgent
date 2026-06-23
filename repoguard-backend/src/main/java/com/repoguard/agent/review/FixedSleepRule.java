package com.repoguard.agent.review;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class FixedSleepRule implements ReviewRule {

    static final String RULE_ID = "RG-JAVA-003";

    private final ReviewFindingFactory findingFactory;

    FixedSleepRule(ReviewFindingFactory findingFactory) {
        this.findingFactory = findingFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<ReviewFindingResult> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !context.trimmedLine().contains("Thread.sleep(")) {
            return Optional.empty();
        }
        return Optional.of(findingFactory.finding(
            "MEDIUM",
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增代码包含固定休眠",
            "请使用可测试的等待条件、重试策略或调度机制。"
        ));
    }
}
