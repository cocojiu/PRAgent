package com.repoguard.agent.review;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class StandardOutputLoggingRule implements ReviewRule {

    static final String RULE_ID = "RG-JAVA-002";

    private final ReviewFindingFactory findingFactory;

    StandardOutputLoggingRule(ReviewFindingFactory findingFactory) {
        this.findingFactory = findingFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<ReviewFindingResult> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !context.trimmedLine().contains("System.out.print")) {
            return Optional.empty();
        }
        return Optional.of(findingFactory.finding(
            "LOW",
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增代码使用了标准输出日志",
            "请改用项目日志组件，避免生产日志不可控。"
        ));
    }
}
