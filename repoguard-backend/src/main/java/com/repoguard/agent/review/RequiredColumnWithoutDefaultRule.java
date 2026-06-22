package com.repoguard.agent.review;

import java.util.Locale;
import java.util.Optional;

class RequiredColumnWithoutDefaultRule implements ReviewRule {

    static final String RULE_ID = "RG-DB-003";

    private final ReviewFindingFactory findingFactory;

    RequiredColumnWithoutDefaultRule(ReviewFindingFactory findingFactory) {
        this.findingFactory = findingFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<ReviewFindingResult> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !addsRequiredColumnWithoutDefault(context)) {
            return Optional.empty();
        }
        return Optional.of(findingFactory.finding(
            "HIGH",
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增非空字段缺少默认值或兼容窗口",
            "请先添加可空字段或默认值，完成历史数据回填后再收紧非空约束。"
        ));
    }

    private boolean addsRequiredColumnWithoutDefault(ReviewRuleLineContext context) {
        if (!isSqlFile(context.filePath())) {
            return false;
        }
        String lower = context.trimmedLine().toLowerCase(Locale.ROOT);
        return lower.matches(".*\\badd\\s+(column\\s+)?[a-z0-9_`\".]+\\s+.*\\bnot\\s+null\\b.*")
            && !lower.contains(" default ");
    }

    private boolean isSqlFile(String filePath) {
        return filePath == null ? false : filePath.replace('\\', '/').toLowerCase(Locale.ROOT).endsWith(".sql");
    }
}
