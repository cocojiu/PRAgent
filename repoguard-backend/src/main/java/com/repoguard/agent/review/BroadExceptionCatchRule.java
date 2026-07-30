package com.repoguard.agent.review;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class BroadExceptionCatchRule implements ReviewRule {

    static final String RULE_ID = "RG-JAVA-001";

    private final RuleMatchFactory matchFactory;

    BroadExceptionCatchRule(RuleMatchFactory matchFactory) {
        this.matchFactory = matchFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<RuleMatch> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !catchesBroadException(context.trimmedLine())) {
            return Optional.empty();
        }
        return Optional.of(matchFactory.match(
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增代码捕获了过宽的异常类型",
            "请捕获更具体的异常类型，并保留必要的错误上下文。"
        ));
    }

    private boolean catchesBroadException(String trimmedLine) {
        return trimmedLine.contains("catch (Exception")
            || trimmedLine.contains("catch(Throwable")
            || trimmedLine.contains("catch (Throwable");
    }
}
