package com.repoguard.agent.review;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class RabbitMessagePublishRule implements ReviewRule {

    static final String RULE_ID = "RG-MQ-001";

    private final RuleMatchFactory matchFactory;

    RabbitMessagePublishRule(RuleMatchFactory matchFactory) {
        this.matchFactory = matchFactory;
    }

    @Override
    public String id() {
        return RULE_ID;
    }

    @Override
    public Optional<RuleMatch> evaluate(ReviewRuleLineContext context) {
        if (!context.isApplicable(id()) || !publishesRabbitMessage(context.trimmedLine())) {
            return Optional.empty();
        }
        return Optional.of(matchFactory.match(
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增 RabbitMQ 发布调用缺少可见补偿语义",
            "请确认发送失败会进入可补偿状态，并记录重试次数、下次重试时间和失败原因。"
        ));
    }

    private boolean publishesRabbitMessage(String trimmedLine) {
        return trimmedLine.contains("rabbitTemplate.convertAndSend")
            || trimmedLine.contains("amqpTemplate.convertAndSend");
    }
}
