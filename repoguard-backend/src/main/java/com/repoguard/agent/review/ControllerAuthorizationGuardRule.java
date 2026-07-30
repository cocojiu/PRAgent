package com.repoguard.agent.review;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class ControllerAuthorizationGuardRule implements ReviewRule {

    static final String RULE_ID = "RG-AUTH-001";

    private final RuleMatchFactory matchFactory;

    ControllerAuthorizationGuardRule(RuleMatchFactory matchFactory) {
        this.matchFactory = matchFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<RuleMatch> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id())
            || !isMutatingControllerMapping(context.filePath(), context.trimmedLine())
            || context.patchHasAuthorizationGuard()) {
            return Optional.empty();
        }
        return Optional.of(matchFactory.match(
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增高危 Controller 写接口缺少显式权限门禁",
            "请为配置写入、评论回写、用户管理等写接口补充 @RequireRole 或等效网关权限控制。"
        ));
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
