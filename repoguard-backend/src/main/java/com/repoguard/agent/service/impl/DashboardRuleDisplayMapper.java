package com.repoguard.agent.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DashboardRuleDisplayMapper {

    static final String DEFAULT_RULE_ID = "LLM";

    public String ruleName(String ruleId) {
        return switch (normalizedRuleId(ruleId)) {
            case "RG-SECRET-001" -> "\u786c\u7f16\u7801\u5bc6\u94a5\u68c0\u6d4b";
            case "RG-API-001" -> "Controller \u65e0\u6d4b\u8bd5";
            case "RG-DB-001" -> "Entity \u65e0\u8fc1\u79fb";
            case "RG-CONFIG-001" -> "\u914d\u7f6e\u53d8\u66f4\u98ce\u9669";
            case "RG-CLEAN-001" -> "TODO/FIXME/System.out";
            case DEFAULT_RULE_ID -> "LLM \u5ba1\u67e5";
            default -> normalizedRuleId(ruleId);
        };
    }

    public String ruleColor(String ruleId) {
        return switch (normalizedRuleId(ruleId)) {
            case "RG-SECRET-001" -> "#ef4444";
            case "RG-API-001" -> "#f59e0b";
            case "RG-DB-001" -> "#2563eb";
            case "RG-CONFIG-001" -> "#22c55e";
            case "RG-CLEAN-001" -> "#6366f1";
            default -> "#14b8a6";
        };
    }

    String normalizedRuleId(String ruleId) {
        return StringUtils.hasText(ruleId) ? ruleId.trim() : DEFAULT_RULE_ID;
    }
}
