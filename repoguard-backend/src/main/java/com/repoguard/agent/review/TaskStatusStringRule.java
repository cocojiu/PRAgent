package com.repoguard.agent.review;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class TaskStatusStringRule implements ReviewRule {

    static final String RULE_ID = "RG-STATE-001";

    private final ReviewFindingFactory findingFactory;

    TaskStatusStringRule(ReviewFindingFactory findingFactory) {
        this.findingFactory = findingFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<ReviewFindingResult> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !writesTaskStatusString(context.trimmedLine())) {
            return Optional.empty();
        }
        return Optional.of(findingFactory.finding(
            "MEDIUM",
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增代码直接写入任务状态字符串",
            "请通过状态机或专门的状态应用边界完成状态流转，避免绕过准入规则和补偿语义。"
        ));
    }

    private boolean writesTaskStatusString(String trimmedLine) {
        return (trimmedLine.contains("setStatus(\"") || trimmedLine.contains("setReviewStatus(\"")
            || trimmedLine.contains("setHumanReviewStatus(\""))
            && (trimmedLine.contains("QUEUED") || trimmedLine.contains("REVIEWING")
                || trimmedLine.contains("COMPLETED") || trimmedLine.contains("FAILED")
                || trimmedLine.contains("PUBLISH_FAILED") || trimmedLine.contains("PENDING_HUMAN_REVIEW"));
    }
}
