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
        assertThat(properties.isSemanticIndexEnabled()).isTrue();
        assertThat(properties.getSemanticIndexMaxFiles()).isEqualTo(24);
        assertThat(properties.getSemanticIndexMaxFileBytes()).isEqualTo(65_536);
        assertThat(properties.getSemanticIndexMaxTotalBytes()).isEqualTo(1_048_576);
        assertThat(properties.getSemanticIndexTimeoutMs()).isEqualTo(3_000);
        assertThat(properties.getSemanticIndexCacheMaximumSize()).isEqualTo(128);
        assertThat(properties.getSemanticIndexCacheTtlSeconds()).isEqualTo(900);
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
        assertThat(properties.getProperty("repoguard.review.llm-context.semantic-index-enabled"))
            .isEqualTo("${REPOGUARD_LLM_SEMANTIC_INDEX_ENABLED:true}");
        assertThat(properties.getProperty("repoguard.review.llm-context.semantic-index-max-files"))
            .isEqualTo("${REPOGUARD_LLM_SEMANTIC_INDEX_MAX_FILES:24}");
        assertThat(properties.getProperty("repoguard.review.llm-context.semantic-index-max-file-bytes"))
            .isEqualTo("${REPOGUARD_LLM_SEMANTIC_INDEX_MAX_FILE_BYTES:65536}");
        assertThat(properties.getProperty("repoguard.review.llm-context.semantic-index-max-total-bytes"))
            .isEqualTo("${REPOGUARD_LLM_SEMANTIC_INDEX_MAX_TOTAL_BYTES:1048576}");
        assertThat(properties.getProperty("repoguard.review.llm-context.semantic-index-timeout-ms"))
            .isEqualTo("${REPOGUARD_LLM_SEMANTIC_INDEX_TIMEOUT_MS:3000}");
        assertThat(properties.getProperty("repoguard.review.llm-context.semantic-index-cache-maximum-size"))
            .isEqualTo("${REPOGUARD_LLM_SEMANTIC_INDEX_CACHE_MAXIMUM_SIZE:128}");
        assertThat(properties.getProperty("repoguard.review.llm-context.semantic-index-cache-ttl-seconds"))
            .isEqualTo("${REPOGUARD_LLM_SEMANTIC_INDEX_CACHE_TTL_SECONDS:900}");
    }
}
