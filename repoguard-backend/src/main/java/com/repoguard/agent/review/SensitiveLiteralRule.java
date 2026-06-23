package com.repoguard.agent.review;

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class SensitiveLiteralRule implements ReviewRule {

    static final String RULE_ID = "RG-SECRET-001";

    private final ReviewFindingFactory findingFactory;

    SensitiveLiteralRule(ReviewFindingFactory findingFactory) {
        this.findingFactory = findingFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<ReviewFindingResult> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !containsSensitiveLiteral(context.trimmedLine())) {
            return Optional.empty();
        }
        return Optional.of(findingFactory.finding(
            "HIGH",
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增代码疑似硬编码敏感信息",
            "请改用加密配置、环境变量或密钥管理服务，并确保响应和日志只返回脱敏值。"
        ));
    }

    private boolean containsSensitiveLiteral(String trimmedLine) {
        String lower = trimmedLine.toLowerCase(Locale.ROOT);
        if (!(lower.contains("password") || lower.contains("secret") || lower.contains("token")
            || lower.contains("apikey") || lower.contains("api_key") || lower.contains("accesskey")
            || lower.contains("access_key"))) {
            return false;
        }
        return trimmedLine.contains("=") && (trimmedLine.contains("\"") || trimmedLine.contains("'"));
    }
}
