package com.repoguard.agent.messaging;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RabbitPublishCompensationSettingsFactory {

    private final RabbitPublishCompensationPolicy compensationPolicy;

    public RabbitPublishCompensationSettingsFactory(RabbitPublishCompensationPolicy compensationPolicy) {
        this.compensationPolicy = Objects.requireNonNull(compensationPolicy, "compensationPolicy");
    }

    public RabbitPublishCompensationSettings create(RabbitPublishCompensationProperties properties) {
        return new RabbitPublishCompensationSettings(properties, compensationPolicy);
    }
}
