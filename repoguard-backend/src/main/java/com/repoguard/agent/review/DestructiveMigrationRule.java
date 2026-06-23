package com.repoguard.agent.review;

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class DestructiveMigrationRule implements ReviewRule {

    static final String RULE_ID = "RG-DB-002";

    private final ReviewFindingFactory findingFactory;

    DestructiveMigrationRule(ReviewFindingFactory findingFactory) {
        this.findingFactory = findingFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<ReviewFindingResult> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !containsDestructiveMigration(context)) {
            return Optional.empty();
        }
        return Optional.of(findingFactory.finding(
            "HIGH",
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增数据库迁移包含破坏性 DDL",
            "请采用 expand-and-contract 兼容迁移，补充数据备份、回滚方案和灰度验证记录。"
        ));
    }

    private boolean containsDestructiveMigration(ReviewRuleLineContext context) {
        if (!isSqlFile(context.filePath())) {
            return false;
        }
        String lower = context.trimmedLine().toLowerCase(Locale.ROOT);
        return lower.matches(".*\\bdrop\\s+table\\b.*") || lower.matches(".*\\bdrop\\s+column\\b.*")
            || lower.matches(".*\\btruncate\\s+table\\b.*");
    }

    private boolean isSqlFile(String filePath) {
        return filePath == null ? false : filePath.replace('\\', '/').toLowerCase(Locale.ROOT).endsWith(".sql");
    }
}
