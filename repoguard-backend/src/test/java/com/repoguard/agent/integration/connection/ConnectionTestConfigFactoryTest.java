package com.repoguard.agent.integration.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.security.SecretCryptoService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ConnectionTestConfigFactoryTest {

    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final ConnectionTestConfigFactory factory = new ConnectionTestConfigFactory(secretCryptoService);

    @Test
    void githubTestConfigKeepsSavedTokenWhenSubmittedTokenIsMasked() {
        IntegrationConfig saved = integrationConfig(secretCryptoService.encrypt("ghp_saved_token"));

        IntegrationConfig config = factory.githubIntegrationForTest(
            "GITHUB",
            new GithubIntegrationConfigRequest(
                " https://api.github.test ",
                "****oken",
                " octocat ",
                " repo "
            ),
            saved
        );

        assertThat(config.getProvider()).isEqualTo("GITHUB");
        assertThat(config.getBaseUrl()).isEqualTo("https://api.github.test");
        assertThat(secretCryptoService.decrypt(config.getTokenValue())).isEqualTo("ghp_saved_token");
        assertThat(config.getDefaultOwner()).isEqualTo("octocat");
        assertThat(config.getDefaultRepo()).isEqualTo("repo");
    }

    @Test
    void serviceTestConfigTreatsBrokenMaskedSavedSecretAsMissing() {
        IntegrationConfig saved = integrationConfig("enc:v2:other:not-readable");

        IntegrationConfig config = factory.serviceIntegrationForTest(
            "MYSQL",
            new ServiceIntegrationConfigRequest(
                " jdbc:mysql://localhost:3306/repoguard ",
                " root ",
                "****cret",
                " repoguard "
            ),
            saved
        );

        assertThat(config.getProvider()).isEqualTo("MYSQL");
        assertThat(config.getBaseUrl()).isEqualTo("jdbc:mysql://localhost:3306/repoguard");
        assertThat(config.getTokenValue()).isNull();
        assertThat(config.getDefaultOwner()).isEqualTo("root");
        assertThat(config.getDefaultRepo()).isEqualTo("repoguard");
    }

    @Test
    void reviewPolicyTestConfigCopiesReviewSettingsAndKeepsMaskedApiKey() {
        ReviewPolicyConfig saved = new ReviewPolicyConfig();
        saved.setApiKeyValue(secretCryptoService.encrypt("sk_saved"));

        ReviewPolicyConfig config = factory.reviewPolicyForTest(reviewPolicyRequest("****aved"), saved);

        assertThat(config.getLlmEnabled()).isTrue();
        assertThat(config.getLlmProvider()).isEqualTo("dashscope");
        assertThat(config.getModelName()).isEqualTo("qwen-plus");
        assertThat(config.getBaseUrl()).isEqualTo("https://dashscope.example/v1");
        assertThat(secretCryptoService.decrypt(config.getApiKeyValue())).isEqualTo("sk_saved");
        assertThat(config.getTimeoutSeconds()).isEqualTo(60);
        assertThat(config.getMaxTokens()).isEqualTo(4096);
        assertThat(config.getChunkMaxFiles()).isEqualTo(4);
        assertThat(config.getOutputTokenPricePerMillion()).isEqualByComparingTo("0.0020");
    }

    private IntegrationConfig integrationConfig(String tokenValue) {
        IntegrationConfig config = new IntegrationConfig();
        config.setTokenValue(tokenValue);
        return config;
    }

    private ReviewPolicyConfigRequest reviewPolicyRequest(String apiKey) {
        return new ReviewPolicyConfigRequest(
            true,
            " dashscope ",
            " qwen-plus ",
            " https://dashscope.example/v1 ",
            apiKey,
            60,
            BigDecimal.valueOf(0.2),
            4096,
            true,
            2,
            6,
            700,
            4,
            450,
            BigDecimal.valueOf(0.0010),
            BigDecimal.valueOf(0.0020)
        );
    }
}
