package com.repoguard.agent.review;

import java.util.Optional;

class TodoCommentRule implements ReviewRule {

    static final String RULE_ID = "RG-GEN-001";

    private final ReviewFindingFactory findingFactory;

    TodoCommentRule(ReviewFindingFactory findingFactory) {
        this.findingFactory = findingFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<ReviewFindingResult> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id())
            || !(context.trimmedLine().contains("TODO") || context.trimmedLine().contains("FIXME"))) {
            return Optional.empty();
        }
        return Optional.of(findingFactory.finding(
            "LOW",
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增代码包含未收敛的 TODO/FIXME",
            "请在合并前补充实现或明确跟踪任务。"
        ));
    }
}
