package com.repoguard.agent.review;

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class SensitiveLoggingRule implements ReviewRule {

    static final String RULE_ID = "RG-LOG-001";

    private final ReviewFindingFactory findingFactory;

    SensitiveLoggingRule(ReviewFindingFactory findingFactory) {
        this.findingFactory = findingFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<ReviewFindingResult> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !logsSensitiveData(context.trimmedLine())) {
            return Optional.empty();
        }
        return Optional.of(findingFactory.finding(
            "HIGH",
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增日志可能输出敏感字段",
            "请记录脱敏摘要或配置标识，避免 GitHub Token、LLM API Key、Webhook secret 等真实密钥进入日志。"
        ));
    }

    private boolean logsSensitiveData(String trimmedLine) {
        String lower = trimmedLine.toLowerCase(Locale.ROOT);
        if (!(lower.contains("log.") || lower.contains("logger."))) {
            return false;
        }
        return lower.contains("token") || lower.contains("secret") || lower.contains("password")
            || lower.contains("apikey") || lower.contains("api_key") || lower.contains("webhook");
    }
}
