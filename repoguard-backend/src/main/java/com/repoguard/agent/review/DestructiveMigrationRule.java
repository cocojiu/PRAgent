package com.repoguard.agent.review;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class DestructiveMigrationRule implements ReviewRule {

    static final String RULE_ID = "RG-DB-002";

    private final RuleMatchFactory matchFactory;
    private final ReviewFilePolicy filePolicy;
    private final SqlMigrationAnalyzer migrationAnalyzer;

    @Autowired
    DestructiveMigrationRule(RuleMatchFactory matchFactory, ReviewFilePolicy filePolicy) {
        this.matchFactory = matchFactory;
        this.filePolicy = filePolicy;
        this.migrationAnalyzer = new SqlMigrationAnalyzer();
    }

    DestructiveMigrationRule(RuleMatchFactory matchFactory) {
        this(matchFactory, ReviewFilePolicy.defaults());
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<RuleMatch> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !containsDestructiveMigration(context)) {
            return Optional.empty();
        }
        boolean verified = context.contextualEvidenceVerified();
        return Optional.of(matchFactory.contextualMatch(
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增数据库迁移包含破坏性 DDL",
            "请采用 expand-and-contract 兼容迁移，补充数据备份、回滚方案和灰度验证记录。",
            verified
                ? "完整 SQL 语句及对象生命周期表明该操作作用于存量生产对象"
                : "完整迁移上下文不可用，仅保留破坏性 DDL 待确认候选",
            verified
        ));
    }

    private boolean containsDestructiveMigration(ReviewRuleLineContext context) {
        if (!isSqlFile(context.filePath()) || filePolicy.nonProduction(context.filePath())) {
            return false;
        }
        return migrationAnalyzer.destructiveStatement(context);
    }

    private boolean isSqlFile(String filePath) {
        return filePath != null && ReviewRuleApplicability.normalizePath(filePath).endsWith(".sql");
    }
}
