package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.security.SecretCryptoService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class LlmPullRequestReviewerTest {

    @Test
    void reviewFallsBackToRulesWhenLlmCircuitIsOpen() {
        ReviewPolicyConfigMapper configMapper = org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
        RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
        SecretCryptoService secretCryptoService = org.mockito.Mockito.mock(SecretCryptoService.class);
        RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
        ExternalCallResilience resilience = org.mockito.Mockito.mock(ExternalCallResilience.class);
        ReviewPolicyConfig config = llmConfig();
        GithubPullRequestDiff diff = new GithubPullRequestDiff("repo-guard-demo", "spring-boot-demo", 512, List.of());

        when(configMapper.selectById(1L)).thenReturn(config);
        when(secretCryptoService.decrypt("enc:v2:local:key")).thenReturn("llm-key");
        when(resilience.llm(eq("chat_completions"), any())).thenThrow(new ExternalCallException(
            "LLM",
            "llm_circuit_open",
            false,
            null,
            "operation=chat_completions",
            null
        ));
        when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed("LOW", List.of()));

        ReviewResult result = new LlmPullRequestReviewer(
            configMapper,
            ruleBasedReviewer,
            RestClient.builder(),
            new ObjectMapper(),
            secretCryptoService,
            metrics,
            resilience
        ).review(new ReviewTask(), diff);

        assertThat(result.llmStatus()).isEqualTo("FALLBACK");
        assertThat(result.statusDetail()).contains("llm_circuit_open");
        assertThat(result.riskLevel()).isEqualTo("LOW");
        verify(metrics).llmFallback("llm_circuit_open");
    }

    private ReviewPolicyConfig llmConfig() {
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        config.setId(1L);
        config.setLlmEnabled(true);
        config.setLlmProvider("openai");
        config.setBaseUrl("https://llm.example.test");
        config.setApiKeyValue("enc:v2:local:key");
        config.setModelName("gpt-test");
        config.setTemperature(BigDecimal.valueOf(0.2));
        config.setMaxTokens(1024);
        config.setTimeoutSeconds(30);
        config.setFallbackToRules(true);
        return config;
    }
}
