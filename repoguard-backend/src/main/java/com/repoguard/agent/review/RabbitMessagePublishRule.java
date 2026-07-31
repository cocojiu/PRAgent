package com.repoguard.agent.review;

import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class RabbitMessagePublishRule implements ReviewRule {

    static final String RULE_ID = "RG-MQ-001";

    private final RuleMatchFactory matchFactory;
    private final ReviewFilePolicy filePolicy;

    @Autowired
    RabbitMessagePublishRule(RuleMatchFactory matchFactory, ReviewFilePolicy filePolicy) {
        this.matchFactory = matchFactory;
        this.filePolicy = filePolicy;
    }

    RabbitMessagePublishRule(RuleMatchFactory matchFactory) {
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
            || filePolicy.approvedMessagePublisher(context.filePath())
            || !publishesRabbitMessage(context.trimmedLine())
            || hasCompensationBoundary(context)) {
            return Optional.empty();
        }
        boolean verified = context.contextualEvidenceVerified();
        return Optional.of(matchFactory.contextualMatch(
            id(),
            context.filePath(),
            context.lineNumber(),
            "新增 RabbitMQ 发布调用缺少可见补偿语义",
            "请确认发送失败会进入可补偿状态，并记录重试次数、下次重试时间和失败原因。",
            verified
                ? "业务边界直接发布消息，完整上下文未发现 Outbox、Confirm 或失败状态记录"
                : "完整文件上下文不可用，仅保留直接发布的待确认候选",
            verified
        ));
    }

    private boolean publishesRabbitMessage(String trimmedLine) {
        return trimmedLine.contains("rabbitTemplate.convertAndSend")
            || trimmedLine.contains("amqpTemplate.convertAndSend");
    }

    private boolean hasCompensationBoundary(ReviewRuleLineContext context) {
        if (!context.fullContextAvailable()) {
            return false;
        }
        String source = context.changedFileContext().content().toLowerCase(Locale.ROOT);
        return source.contains("setconfirmcallback")
            || source.contains("correlationdata")
            || source.contains("waitforconfirms")
            || source.contains("publishfailurestore")
            || source.contains("recordpublishfailure")
            || source.contains("recordfailure")
            || source.contains("outbox")
            || source.contains("publishpersisted")
            || source.contains("failurestatus");
    }
}
