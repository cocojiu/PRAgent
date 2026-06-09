package com.repoguard.agent.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRuleMetricDto;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.service.SystemConfigService;
import java.math.BigDecimal;
import java.util.List;
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

        @Override
        public ReviewRulesResponse getReviewRules() {
            return reviewRulesDto();
        }

        @Override
        public ReviewRuleConfigDto createReviewRule(ReviewRuleConfigRequest request) {
            return reviewRuleDto();
        }

        @Override
        public ReviewRuleConfigDto updateReviewRule(String id, ReviewRuleConfigRequest request) {
            return reviewRuleDto();
        }

        @Override
        public ReviewRuleConfigDto updateReviewRuleStatus(String id, String status) {
            return new ReviewRuleConfigDto(
                id.toUpperCase(),
                "异常捕获过宽",
                "Java Patch",
                "medium",
                status,
                3,
                "88%",
                "2026-06-09 12:00:00",
                "检测 catch Exception 等过宽异常捕获。"
            );
        }

        @Override
        public ConnectionTestResultDto testGithubIntegration() {
            return connectionResult();
        }

        @Override
        public ConnectionTestResultDto testReviewPolicy() {
            return connectionResult();
        }

        @Override
        public ConnectionTestResultDto testMysqlConnection() {
            return connectionResult();
        }

        @Override
        public ConnectionTestResultDto testRabbitMqConnection() {
            return connectionResult();
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

    @Test
    void testGithubIntegrationReturnsConnectionResult() throws Exception {
        mockMvc.perform(post("/api/v1/config/integrations/github/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.success").value(true))
            .andExpect(jsonPath("$.data.status").value("connected"));
    }

    @Test
    void getReviewRulesReturnsRulesAndMetrics() throws Exception {
        mockMvc.perform(get("/api/v1/config/review-rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.metrics[0].label").value("启用规则"))
            .andExpect(jsonPath("$.data.rules[0].id").value("RG-JAVA-001"))
            .andExpect(jsonPath("$.data.rules[0].status").value("enabled"));
    }

    @Test
    void updateReviewRuleStatusReturnsUpdatedRule() throws Exception {
        mockMvc.perform(put("/api/v1/config/review-rules/RG-JAVA-001/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "status": "disabled"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value("RG-JAVA-001"))
            .andExpect(jsonPath("$.data.status").value("disabled"));
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

    private ConnectionTestResultDto connectionResult() {
        return new ConnectionTestResultDto(true, "connected", "Connection test succeeded", "2026-06-06 15:30:00");
    }

    private ReviewRulesResponse reviewRulesDto() {
        return new ReviewRulesResponse(
            List.of(new ReviewRuleMetricDto("启用规则", "1", "共 1 条规则", "blue")),
            List.of(reviewRuleDto())
        );
    }

    private ReviewRuleConfigDto reviewRuleDto() {
        return new ReviewRuleConfigDto(
            "RG-JAVA-001",
            "异常捕获过宽",
            "Java Patch",
            "medium",
            "enabled",
            3,
            "88%",
            "2026-06-09 12:00:00",
            "检测 catch Exception 等过宽异常捕获。"
        );
    }
}
