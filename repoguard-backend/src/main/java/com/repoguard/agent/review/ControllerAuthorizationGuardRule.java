package com.repoguard.agent.review;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ControllerAuthorizationGuardRule implements ReviewRule {

    static final String RULE_ID = "RG-AUTH-001";

    private final RuleMatchFactory matchFactory;
    private final ReviewFilePolicy filePolicy;
    private final AuthorizationBoundaryAnalyzer boundaryAnalyzer;

    @Autowired
    ControllerAuthorizationGuardRule(
        RuleMatchFactory matchFactory,
        ReviewFilePolicy filePolicy
    ) {
        this.matchFactory = matchFactory;
        this.filePolicy = filePolicy;
        this.boundaryAnalyzer = new AuthorizationBoundaryAnalyzer();
    }

    ControllerAuthorizationGuardRule(RuleMatchFactory matchFactory) {
        this(matchFactory, ReviewFilePolicy.defaults());
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<RuleMatch> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id())
            || !isMutatingControllerMapping(context.filePath(), context.trimmedLine())
            || filePolicy.nonProduction(context.filePath())
            || filePolicy.approvedAuthorizationBoundary(context.filePath())
            || (!context.fullContextAvailable() && context.patchHasAuthorizationGuard())
            || hasFullContextBoundary(context)) {
            return Optional.empty();
        }
        boolean verified = context.contextualEvidenceVerified();
        return Optional.of(matchFactory.contextualMatch(
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增高危 Controller 写接口缺少显式权限门禁",
            "请为配置写入、评论回写、用户管理等写接口补充 @RequireRole 或等效网关权限控制。",
            verified
                ? "完整文件及相邻声明未发现适用的授权边界"
                : "完整文件上下文不可用，仅保留待确认的写接口候选",
            verified
        ));
    }

    private boolean hasFullContextBoundary(ReviewRuleLineContext context) {
        return context.fullContextAvailable() && boundaryAnalyzer.hasApplicableBoundary(
            context.changedFileContext().content(),
            context.lineNumber(),
            context.trimmedLine()
        );
    }

    private boolean isMutatingControllerMapping(String filePath, String trimmedLine) {
        String normalizedPath = ReviewRuleApplicability.normalizePath(filePath);
        if (!normalizedPath.endsWith("controller.java")) {
            return false;
        }
        return trimmedLine.contains("@PostMapping")
            || trimmedLine.contains("@PutMapping")
            || trimmedLine.contains("@PatchMapping")
            || trimmedLine.contains("@DeleteMapping");
    }
}
