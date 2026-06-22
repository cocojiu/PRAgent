package com.repoguard.agent.review;

import java.util.Locale;

class ReviewFindingFactory {

    ReviewFindingResult finding(
        String severity,
        String ruleId,
        String filePath,
        Integer lineNumber,
        String message,
        String recommendation
    ) {
        return new ReviewFindingResult(
            severity,
            "RULE",
            ruleId,
            filePath,
            lineNumber,
            message,
            recommendation,
            confidenceFor(severity),
            "规则 " + ruleId + " 命中新增代码行 " + (lineNumber == null ? "unknown" : lineNumber),
            impactFor(ruleId),
            recommendation,
            isBlockingSeverity(severity),
            reviewDimensionFor(ruleId)
        );
    }

    private String confidenceFor(String severity) {
        if (severity == null) {
            return "LOW";
        }
        return switch (severity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "HIGH" -> "HIGH";
            case "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private boolean isBlockingSeverity(String severity) {
        return severity != null && switch (severity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "HIGH" -> true;
            default -> false;
        };
    }

    private String reviewDimensionFor(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            return "RULE";
        }
        if (ruleId.startsWith("RG-DB")) {
            return "DATABASE_COMPATIBILITY_RULE";
        }
        if (ruleId.startsWith("RG-GH")) {
            return "GITHUB_WRITEBACK_RULE";
        }
        if (ruleId.startsWith("RG-API")) {
            return "API_CONTRACT_RULE";
        }
        if (ruleId.startsWith("RG-AUTH") || ruleId.startsWith("RG-SECRET") || ruleId.startsWith("RG-LOG")) {
            return "SECURITY_RULE";
        }
        if (ruleId.startsWith("RG-MQ")) {
            return "MESSAGE_RELIABILITY_RULE";
        }
        if (ruleId.startsWith("RG-EXT")) {
            return "EXTERNAL_CALL_RULE";
        }
        return "PROJECT_RULE";
    }

    private String impactFor(String ruleId) {
        if (ruleId == null) {
            return "可能影响代码质量和长期维护成本。";
        }
        return switch (ruleId) {
            case "RG-AUTH-001" -> "高危写接口缺少门禁可能导致配置、用户或评论回写能力被越权调用。";
            case "RG-SECRET-001", "RG-LOG-001" -> "敏感信息进入代码、响应或日志后可能造成 Token 泄露和供应链访问风险。";
            case "RG-STATE-001" -> "绕过状态机可能破坏任务幂等、补偿和人工复核准入语义。";
            case "RG-MQ-001" -> "消息发布失败不可见会导致任务卡死、重复消费或无法补偿。";
            case "RG-EXT-001" -> "外部调用缺少治理可能造成请求阻塞、重试风暴或故障扩散。";
            case "RG-DB-002" -> "破坏性迁移可能导致灰度发布失败、历史数据不可恢复或回滚困难。";
            case "RG-DB-003" -> "新增非空字段缺少兼容策略可能导致历史数据升级或多版本应用启动失败。";
            case "RG-GH-001" -> "绕过回写幂等记录可能导致 GitHub PR 被重复刷评论且审计链路缺失。";
            default -> "可能影响线上稳定性、可维护性或审查结果可信度。";
        };
    }
}
