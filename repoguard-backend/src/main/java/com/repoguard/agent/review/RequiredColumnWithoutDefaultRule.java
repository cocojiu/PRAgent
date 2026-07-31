package com.repoguard.agent.review;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class RequiredColumnWithoutDefaultRule implements ReviewRule {

    static final String RULE_ID = "RG-DB-003";

    private final RuleMatchFactory matchFactory;
    private final ReviewFilePolicy filePolicy;
    private final SqlMigrationAnalyzer migrationAnalyzer;

    @Autowired
    RequiredColumnWithoutDefaultRule(RuleMatchFactory matchFactory, ReviewFilePolicy filePolicy) {
        this.matchFactory = matchFactory;
        this.filePolicy = filePolicy;
        this.migrationAnalyzer = new SqlMigrationAnalyzer();
    }

    RequiredColumnWithoutDefaultRule(RuleMatchFactory matchFactory) {
        this(matchFactory, ReviewFilePolicy.defaults());
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<RuleMatch> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !addsRequiredColumnWithoutDefault(context)) {
            return Optional.empty();
        }
        boolean verified = context.contextualEvidenceVerified();
        return Optional.of(matchFactory.contextualMatch(
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增非空字段缺少默认值或兼容窗口",
            "请先添加可空字段或默认值，完成历史数据回填后再收紧非空约束。",
            verified
                ? "完整 SQL 语句显示存量表直接新增无默认值的非空字段"
                : "完整迁移上下文不可用，仅保留非空字段待确认候选",
            verified
        ));
    }

    private boolean addsRequiredColumnWithoutDefault(ReviewRuleLineContext context) {
        if (!isSqlFile(context.filePath()) || filePolicy.nonProduction(context.filePath())) {
            return false;
        }
        return migrationAnalyzer.requiredColumnWithoutCompatibilityWindow(context);
    }

    private boolean isSqlFile(String filePath) {
        return filePath != null && ReviewRuleApplicability.normalizePath(filePath).endsWith(".sql");
    }
}
