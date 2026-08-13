package com.repoguard.agent.review;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class SensitiveLiteralRule implements ReviewRule {

    static final String RULE_ID = "RG-SECRET-001";
    private static final Pattern SENSITIVE_TARGET = Pattern.compile(
        "(?i).*(password|secret|token|api_?key|access_?key|credential).*"
    );
    private static final Pattern KNOWN_CREDENTIAL = Pattern.compile(
        "(?i)(?:"
            + "gh[pousr]_[a-z0-9_]{20,}"
            + "|sk-[a-z0-9][a-z0-9_-]{15,}"
            + "|whsec_[a-z0-9_-]{12,}"
            + "|AKIA[0-9A-Z]{16}"
            + "|xox[baprs]-[a-z0-9-]{12,}"
            + "|eyJ[a-z0-9_-]{10,}\\.[a-z0-9_-]{10,}\\.[a-z0-9_-]{8,}"
            + ")"
    );

    private final RuleMatchFactory matchFactory;
    private final ReviewFilePolicy filePolicy;

    @Autowired
    SensitiveLiteralRule(RuleMatchFactory matchFactory, ReviewFilePolicy filePolicy) {
        this.matchFactory = matchFactory;
        this.filePolicy = filePolicy;
    }

    SensitiveLiteralRule(RuleMatchFactory matchFactory) {
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
            || !containsSensitiveLiteral(context.trimmedLine())) {
            return Optional.empty();
        }
        return Optional.of(matchFactory.contextualMatch(
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增代码疑似硬编码敏感信息",
            "请改用加密配置、环境变量或密钥管理服务，并确保响应和日志只返回脱敏值。",
            "赋值目标和字面量形态共同表明该值可能是真实凭据",
            true
        ));
    }

    private boolean containsSensitiveLiteral(String trimmedLine) {
        Optional<StructuredJavaCallParser.Assignment> assignment =
            StructuredJavaCallParser.assignment(trimmedLine);
        if (assignment.isEmpty() || !literalOnly(assignment.get().expression())) {
            return false;
        }
        String target = assignment.get().target();
        String value = String.join(
            "",
            StructuredJavaCallParser.stringLiteralFragments(assignment.get().expression())
        );
        if (value.isBlank() || placeholder(value) || keyNameConstant(target, value)) {
            return false;
        }
        if (KNOWN_CREDENTIAL.matcher(value).find()) {
            return true;
        }
        return SENSITIVE_TARGET.matcher(target).matches()
            && value.length() >= 12
            && shannonEntropy(value) >= 2.8d;
    }

    private boolean literalOnly(String expression) {
        if (StructuredJavaCallParser.stringLiteralFragments(expression).isEmpty()) {
            return false;
        }
        String remainder = expression.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "")
            .replaceAll("'(?:\\\\.|[^'\\\\])*'", "")
            .replaceAll("[+()\\s]", "");
        return remainder.isEmpty();
    }

    private boolean placeholder(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()
            || normalized.matches("[*x•_-]{4,}")
            || normalized.matches("\\$\\{[^}]+}")
            || normalized.matches("\\{\\{[^}]+}}")
            || normalized.matches("%[a-z0-9_]+%")) {
            return true;
        }
        return normalized.equals("demo")
            || normalized.equals("example")
            || normalized.equals("sample")
            || normalized.equals("dummy")
            || normalized.equals("changeme")
            || normalized.equals("change-me")
            || normalized.equals("placeholder")
            || normalized.equals("test-token")
            || normalized.startsWith("your-")
            || normalized.startsWith("your_")
            || normalized.startsWith("replace-")
            || normalized.startsWith("replace_");
    }

    private boolean keyNameConstant(String target, String value) {
        String normalizedTarget = target == null ? "" : target.trim();
        String lowerTarget = normalizedTarget.toLowerCase(Locale.ROOT);
        String lowerValue = value.toLowerCase(Locale.ROOT);
        return normalizedTarget.matches("[A-Z0-9_]+_KEY")
            || lowerTarget.endsWith("keyname")
            || lowerTarget.endsWith("propertyname")
            || (lowerValue.matches("[a-z0-9_.-]+")
                && lowerValue.contains(".")
                && (lowerTarget.endsWith("key") || lowerTarget.endsWith("name")));
    }

    private double shannonEntropy(String value) {
        int[] asciiFrequencies = new int[128];
        Map<Integer, Integer> extendedFrequencies = new HashMap<>();
        value.chars().forEach(character -> {
            if (character < asciiFrequencies.length) {
                asciiFrequencies[character]++;
            } else {
                extendedFrequencies.merge(character, 1, Integer::sum);
            }
        });
        double entropy = 0.0d;
        for (int frequency : asciiFrequencies) {
            if (frequency == 0) {
                continue;
            }
            double probability = (double) frequency / value.length();
            entropy -= probability * (Math.log(probability) / Math.log(2));
        }
        for (int frequency : extendedFrequencies.values()) {
            double probability = (double) frequency / value.length();
            entropy -= probability * (Math.log(probability) / Math.log(2));
        }
        return entropy;
    }
}
