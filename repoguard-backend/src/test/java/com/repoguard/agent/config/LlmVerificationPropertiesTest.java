package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.review.EnforcementMode;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class LlmVerificationPropertiesTest {

    @Test
    void defaultsToBoundedCommentOnlyVerification() {
        LlmVerificationProperties properties = new LlmVerificationProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getMaxCandidates()).isEqualTo(4);
        assertThat(properties.enforcementMode()).isEqualTo(EnforcementMode.COMMENT);
    }

    @Test
    void normalizesAndRejectsInvalidEnforcementModes() {
        LlmVerificationProperties properties = new LlmVerificationProperties();

        properties.setEnforcementMode(" block ");
        assertThat(properties.getEnforcementMode()).isEqualTo("BLOCK");
        assertThat(properties.enforcementMode()).isEqualTo(EnforcementMode.BLOCK);
        assertThatThrownBy(() -> properties.setEnforcementMode("auto"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported review rule enforcementMode");
    }

    @Test
    void applicationYamlExposesVerificationGate() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("repoguard.review.llm-verification.enabled"))
            .isEqualTo("${REPOGUARD_LLM_VERIFICATION_ENABLED:true}");
        assertThat(properties.getProperty("repoguard.review.llm-verification.max-candidates"))
            .isEqualTo("${REPOGUARD_LLM_VERIFICATION_MAX_CANDIDATES:4}");
        assertThat(properties.getProperty("repoguard.review.llm-verification.enforcement-mode"))
            .isEqualTo("${REPOGUARD_LLM_VERIFICATION_ENFORCEMENT_MODE:COMMENT}");
    }
}
