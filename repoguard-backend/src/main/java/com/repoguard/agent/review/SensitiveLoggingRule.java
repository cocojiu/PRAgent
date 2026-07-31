package com.repoguard.agent.review;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class SensitiveLoggingRule implements ReviewRule {

    static final String RULE_ID = "RG-LOG-001";
    private static final Pattern LOG_INVOCATION = Pattern.compile(
        "\\b(log|logger)\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SENSITIVE_EXPRESSION = Pattern.compile(
        "(?i).*\\b\\w*(token|password|secret|api_?key|access_?key|credential|authorization|bearer|webhook)\\w*\\b.*"
    );
    private static final Pattern SAFE_STATE_EXPRESSION = Pattern.compile(
        "(?i).*\\b\\w*(configured|enabled|present|exists|invalid|status|count|length|id)\\w*\\b.*"
    );

    private final RuleMatchFactory matchFactory;
    private final ReviewFilePolicy filePolicy;

    @Autowired
    SensitiveLoggingRule(RuleMatchFactory matchFactory, ReviewFilePolicy filePolicy) {
        this.matchFactory = matchFactory;
        this.filePolicy = filePolicy;
    }

    SensitiveLoggingRule(RuleMatchFactory matchFactory) {
        this(matchFactory, ReviewFilePolicy.defaults());
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<RuleMatch> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id())
            || filePolicy.nonProduction(context.filePath())
            || !logsSensitiveData(context)) {
            return Optional.empty();
        }
        return Optional.of(matchFactory.contextualMatch(
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增日志可能输出敏感字段",
            "请记录脱敏摘要或配置标识，避免 GitHub Token、LLM API Key、Webhook secret 等真实密钥进入日志。",
            "日志调用的值参数包含未脱敏的敏感值表达式",
            true
        ));
    }

    private boolean logsSensitiveData(ReviewRuleLineContext context) {
        Optional<StructuredJavaCallParser.Invocation> invocation =
            StructuredJavaCallParser.invocation(context.trimmedLine(), LOG_INVOCATION);
        if (invocation.isEmpty() && LOG_INVOCATION.matcher(context.trimmedLine()).find()) {
            invocation = StructuredJavaCallParser.invocation(
                StructuredJavaCallParser.statementStartingAt(context),
                LOG_INVOCATION
            );
        }
        if (invocation.isEmpty()) {
            return false;
        }
        List<String> arguments = invocation.get().arguments();
        if (arguments.isEmpty()) {
            return false;
        }
        if (containsSensitiveValueExpression(withoutStringLiterals(arguments.getFirst()))) {
            return true;
        }
        return arguments.stream().skip(1).anyMatch(this::containsSensitiveValueExpression);
    }

    private boolean containsSensitiveValueExpression(String expression) {
        if (expression == null || expression.isBlank() || filePolicy.approvedRedactionExpression(expression)) {
            return false;
        }
        if (SAFE_STATE_EXPRESSION.matcher(expression).matches()) {
            return false;
        }
        return SENSITIVE_EXPRESSION.matcher(expression).matches();
    }

    private String withoutStringLiterals(String expression) {
        StringBuilder result = new StringBuilder(expression.length());
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                result.append(' ');
            } else if (current == '"' || current == '\'') {
                quote = current;
                result.append(' ');
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}
