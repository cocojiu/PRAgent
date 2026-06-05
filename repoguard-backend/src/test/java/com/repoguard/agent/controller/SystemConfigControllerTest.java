package com.repoguard.agent.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.service.SystemConfigService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SystemConfigControllerTest {

    private final SystemConfigService systemConfigService = new SystemConfigService() {
        @Override
        public GithubIntegrationConfigDto getGithubIntegration() {
            return githubDto();
        }

        @Override
        public GithubIntegrationConfigDto updateGithubIntegration(GithubIntegrationConfigRequest request) {
            return githubDto();
        }

        @Override
        public ReviewPolicyConfigDto getReviewPolicy() {
            return reviewPolicyDto();
        }

        @Override
        public ReviewPolicyConfigDto updateReviewPolicy(ReviewPolicyConfigRequest request) {
            return reviewPolicyDto();
        }
    };

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SystemConfigController(systemConfigService)).build();

    @Test
    void getGithubIntegrationReturnsMaskedConfig() throws Exception {
        mockMvc.perform(get("/api/v1/config/integrations/github"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.provider").value("GITHUB"))
            .andExpect(jsonPath("$.data.token").value("****1234"));
    }

    @Test
    void updateReviewPolicyReturnsMaskedApiKey() throws Exception {
        mockMvc.perform(put("/api/v1/config/review-policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "llmEnabled": true,
                      "llmProvider": "dashscope",
                      "modelName": "qwen-plus",
                      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
                      "apiKey": "sk-new-secret",
                      "timeoutSeconds": 60,
                      "temperature": 0.2,
                      "maxTokens": 4096,
                      "fallbackToRules": true,
                      "workerConcurrency": 1
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.apiKey").value("****5678"));
    }

    private GithubIntegrationConfigDto githubDto() {
        return new GithubIntegrationConfigDto(
            "GITHUB",
            "configured",
            "https://api.github.com",
            "****1234",
            "repo-guard-demo",
            "spring-boot-demo",
            null,
            null,
            "2026-06-06 01:30:00"
        );
    }

    private ReviewPolicyConfigDto reviewPolicyDto() {
        return new ReviewPolicyConfigDto(
            true,
            "dashscope",
            "qwen-plus",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "****5678",
            60,
            BigDecimal.valueOf(0.20),
            4096,
            true,
            1,
            "2026-06-06 01:30:00"
        );
    }
}
