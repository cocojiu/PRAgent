package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RabbitMessagePublishRuleTest {

    private final RabbitMessagePublishRule rule = new RabbitMessagePublishRule(new RuleMatchFactory());

    @Test
    void evaluatesRabbitTemplatePublishCall() {
        var finding = rule.evaluate(context(
            "src/RabbitPublisher.java",
            "rabbitTemplate.convertAndSend(exchange, routingKey, message);",
            Map.of()
        ));

        assertThat(finding).isPresent();
        assertThat(finding.get().ruleId()).isEqualTo("RG-MQ-001");
        assertThat(finding.get().filePath()).isEqualTo("src/RabbitPublisher.java");
        assertThat(finding.get().lineNumber()).isEqualTo(44);
        assertThat(finding.get().reviewDimension()).isEqualTo("MESSAGE_RELIABILITY_RULE");
    }

    @Test
    void evaluatesAmqpTemplatePublishCall() {
        assertThat(rule.evaluate(context(
            "src/RabbitPublisher.java",
            "amqpTemplate.convertAndSend(exchange, routingKey, message);",
            Map.of()
        ))).isPresent();
    }

    @Test
    void skipsWhenLineDoesNotPublishRabbitMessage() {
        assertThat(rule.evaluate(context("src/App.java", "rabbitTemplate.receive(queue);", Map.of()))).isEmpty();
        assertThat(rule.evaluate(context("src/App.java", "messagePublisher.publish(message);", Map.of()))).isEmpty();
    }

    @Test
    void skipsWhenRuleIsDisabled() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            RabbitMessagePublishRule.RULE_ID,
            new ReviewRuleSettings(RabbitMessagePublishRule.RULE_ID, "DISABLED", "")
        );

        assertThat(rule.evaluate(context(
            "src/App.java",
            "rabbitTemplate.convertAndSend(exchange, routingKey, message);",
            rules
        ))).isEmpty();
    }

    @Test
    void skipsWhenFilePatternDoesNotMatch() {
        Map<String, ReviewRuleSettings> rules = Map.of(
            RabbitMessagePublishRule.RULE_ID,
            new ReviewRuleSettings(RabbitMessagePublishRule.RULE_ID, "ENABLED", "*.java")
        );

        assertThat(rule.evaluate(context(
            "docs/README.md",
            "rabbitTemplate.convertAndSend(exchange, routingKey, message);",
            rules
        ))).isEmpty();
    }

    private ReviewRuleLineContext context(String filePath, String line, Map<String, ReviewRuleSettings> configuredRules) {
        return new ReviewRuleLineContext(
            filePath,
            44,
            line,
            line.trim(),
            ReviewRuleTestFixtures.configuredOrDefault(rule.id(), configuredRules)
        );
    }
}
