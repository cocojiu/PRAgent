package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class LlmReviewContextPropertiesTest {

    @Test
    void defaultsBoundPromptContextAndRulePolicyText() {
        LlmReviewContextProperties properties = new LlmReviewContextProperties();

        assertThat(properties.getMaxTotalChars()).isEqualTo(24_000);
        assertThat(properties.getMaxSliceChars()).isEqualTo(6_000);
        assertThat(properties.getMaxRelatedFiles()).isEqualTo(8);
        assertThat(properties.getMaxRulePolicies()).isEqualTo(20);
        assertThat(properties.getMaxRuleTextChars()).isEqualTo(360);
    }

    @Test
    void applicationYamlExposesEveryPromptContextBudget() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("repoguard.review.llm-context.max-total-chars"))
            .isEqualTo("${REPOGUARD_LLM_CONTEXT_MAX_TOTAL_CHARS:24000}");
        assertThat(properties.getProperty("repoguard.review.llm-context.max-slice-chars"))
            .isEqualTo("${REPOGUARD_LLM_CONTEXT_MAX_SLICE_CHARS:6000}");
        assertThat(properties.getProperty("repoguard.review.llm-context.max-related-files"))
            .isEqualTo("${REPOGUARD_LLM_CONTEXT_MAX_RELATED_FILES:8}");
        assertThat(properties.getProperty("repoguard.review.llm-context.max-rule-policies"))
            .isEqualTo("${REPOGUARD_LLM_CONTEXT_MAX_RULE_POLICIES:20}");
        assertThat(properties.getProperty("repoguard.review.llm-context.max-rule-text-chars"))
            .isEqualTo("${REPOGUARD_LLM_CONTEXT_MAX_RULE_TEXT_CHARS:360}");
    }
}
