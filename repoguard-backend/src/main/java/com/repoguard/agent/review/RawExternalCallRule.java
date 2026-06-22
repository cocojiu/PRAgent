package com.repoguard.agent.review;

import java.util.Optional;

class RawExternalCallRule implements ReviewRule {

    static final String RULE_ID = "RG-EXT-001";

    private final ReviewFindingFactory findingFactory;

    RawExternalCallRule(ReviewFindingFactory findingFactory) {
        this.findingFactory = findingFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<ReviewFindingResult> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !performsRawExternalCall(context.trimmedLine())) {
            return Optional.empty();
        }
        return Optional.of(findingFactory.finding(
            "MEDIUM",
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增外部调用缺少显式治理边界",
            "请通过 ExternalCallResilience 或等效封装补充超时、错误分类、限流/熔断和指标记录。"
        ));
    }

    private boolean performsRawExternalCall(String trimmedLine) {
        return (trimmedLine.contains("restClient.") || trimmedLine.contains("webClient.")
            || trimmedLine.contains("RestTemplate") || trimmedLine.contains("HttpClient.newHttpClient"))
            && (trimmedLine.contains(".retrieve()") || trimmedLine.contains(".exchange(")
                || trimmedLine.contains(".send(") || trimmedLine.contains(".body("));
    }
}
