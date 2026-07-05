package com.repoguard.agent.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.common.GlobalExceptionHandler;
import com.repoguard.agent.dto.BaseSettingsDto;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.NotificationSettingsDto;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ReviewPolicySettingsDto;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRuleMetricDto;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.dto.SecuritySettingsDto;
import com.repoguard.agent.dto.SecretReEncryptionItemDto;
import com.repoguard.agent.dto.SecretReEncryptionRequest;
import com.repoguard.agent.dto.SecretReEncryptionResponse;
import com.repoguard.agent.dto.ServiceIntegrationConfigDto;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.dto.SettingLogDto;
import com.repoguard.agent.dto.SystemSettingsDto;
import com.repoguard.agent.dto.SystemSettingsRequest;
import com.repoguard.agent.security.SecretReEncryptionService;
import com.repoguard.agent.service.SystemConfigService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
        public ServiceIntegrationConfigDto getMysqlIntegration() {
            return mysqlDto();
        }

        @Override
        public ServiceIntegrationConfigDto updateMysqlIntegration(ServiceIntegrationConfigRequest request) {
            return mysqlDto();
        }

        @Override
        public ServiceIntegrationConfigDto getRabbitMqIntegration() {
            return rabbitMqDto();
        }

        @Override
        public ServiceIntegrationConfigDto updateRabbitMqIntegration(ServiceIntegrationConfigRequest request) {
            return rabbitMqDto();
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
        public SystemSettingsDto getSystemSettings() {
            return systemSettingsDto();
        }

        @Override
        public SystemSettingsDto updateSystemSettings(SystemSettingsRequest request) {
            return systemSettingsDto();
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
                "Java",
                "*.java",
                "medium",
                status,
                3,
                "88%",
                "2026-06-09 12:00:00",
                "检测 catch Exception 等过宽异常捕获。"
            );
        }

        @Override
        public ConnectionTestResultDto testGithubIntegration(GithubIntegrationConfigRequest request) {
            return connectionResult();
        }

        @Override
        public ConnectionTestResultDto testReviewPolicy(ReviewPolicyConfigRequest request) {
            return connectionResult();
        }

        @Override
        public ConnectionTestResultDto testMysqlConnection(ServiceIntegrationConfigRequest request) {
            return connectionResult();
        }

        @Override
        public ConnectionTestResultDto testRabbitMqConnection(ServiceIntegrationConfigRequest request) {
            return connectionResult();
        }
    };

    private final SecretReEncryptionService secretReEncryptionService = Mockito.mock(SecretReEncryptionService.class);
    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new SystemConfigController(systemConfigService, secretReEncryptionService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @Test
    void getGithubIntegrationReturnsMaskedConfig() throws Exception {
        mockMvc.perform(get("/api/v1/config/integrations/github"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.provider").value("GITHUB"))
            .andExpect(jsonPath("$.data.token").value("****1234"));
    }

    @Test
    void getMysqlIntegrationReturnsMaskedConfig() throws Exception {
        mockMvc.perform(get("/api/v1/config/integrations/mysql"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.provider").value("MYSQL"))
            .andExpect(jsonPath("$.data.secret").value("****1234"))
            .andExpect(jsonPath("$.data.resource").value("repoguard"));
    }

    @Test
    void updateRabbitMqIntegrationReturnsMaskedConfig() throws Exception {
        mockMvc.perform(put("/api/v1/config/integrations/rabbitmq")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "baseUrl": "amqp://localhost:5672",
                      "username": "repoguard",
                      "secret": "repoguard-secret",
                      "resource": "/"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.provider").value("RABBITMQ"))
            .andExpect(jsonPath("$.data.secret").value("****5678"));
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
                      "workerConcurrency": 1,
                      "chunkFileThreshold": 6,
                      "chunkLineThreshold": 700,
                      "chunkMaxFiles": 4,
                      "chunkMaxLines": 450,
                      "inputTokenPricePerMillion": 0.5,
                      "outputTokenPricePerMillion": 1.5
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.apiKey").value("****5678"));
    }

    @Test
    void getSystemSettingsReturnsFullSettings() throws Exception {
        mockMvc.perform(get("/api/v1/config/system-settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.base.systemName").value("RepoGuard Agent"))
            .andExpect(jsonPath("$.data.policy.llmTimeoutSeconds").value(60))
            .andExpect(jsonPath("$.data.notification.githubComment").value(true))
            .andExpect(jsonPath("$.data.security.webhookSignature").value(true))
            .andExpect(jsonPath("$.data.logs[0].action").value("更新系统设置"));
    }

    @Test
    void updateSystemSettingsReturnsSavedSettings() throws Exception {
        mockMvc.perform(put("/api/v1/config/system-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "base": {
                        "systemName": "RepoGuard Agent",
                        "language": "中文",
                        "timezone": "Asia/Shanghai",
                        "retentionDays": 120
                      },
                      "policy": {
                        "maxDiffLines": 1200,
                        "llmTimeoutSeconds": 90,
                        "workerConcurrency": 3,
                        "autoComment": true,
                        "autoRetry": false
                      },
                      "notification": {
                        "githubComment": true,
                        "highRiskPr": true,
                        "failedTask": false,
                        "email": "ops@repoguard.dev"
                      },
                      "security": {
                        "webhookSignature": true,
                        "secretMasking": true,
                        "publicRepoAllowed": false,
                        "tokenTtlDays": 45
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.base.systemName").value("RepoGuard Agent"))
            .andExpect(jsonPath("$.data.policy.maxDiffLines").value(800));
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
    void testMysqlIntegrationAcceptsCurrentFormConfig() throws Exception {
        mockMvc.perform(post("/api/v1/config/integrations/mysql/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "baseUrl": "jdbc:mysql://localhost:3306/repoguard",
                      "username": "root",
                      "secret": "root-password",
                      "resource": "repoguard"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("connected"));
    }

    @Test
    void getReviewRulesReturnsRulesAndMetrics() throws Exception {
        mockMvc.perform(get("/api/v1/config/review-rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.metrics[0].label").value("启用规则"))
            .andExpect(jsonPath("$.data.rules[0].id").value("RG-JAVA-001"))
            .andExpect(jsonPath("$.data.rules[0].status").value("enabled"))
            .andExpect(jsonPath("$.data.rules[0].applicableLanguages").value("Java"))
            .andExpect(jsonPath("$.data.rules[0].filePatterns").value("*.java"));
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

    @Test
    void reEncryptSecretsReturnsOperationReport() throws Exception {
        Mockito.when(secretReEncryptionService.reEncrypt(org.mockito.ArgumentMatchers.any(SecretReEncryptionRequest.class)))
            .thenReturn(new SecretReEncryptionResponse(
                false,
                1,
                1,
                0,
                0,
                List.of(new SecretReEncryptionItemDto(
                    "integration_config",
                    1L,
                    "token_value",
                    "GITHUB",
                    "enc:v2",
                    "old-2026",
                    "new-2026",
                    "WOULD_RE_ENCRYPT",
                    "Secret can be re-encrypted"
                ))
            ));

        mockMvc.perform(post("/api/v1/config/secrets/re-encryption")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sourceEncryptionKey": "Old-Encryption-Key-2026!Rotate-Primary",
                      "sourceKeyId": "old-2026",
                      "targetEncryptionKey": "New-Encryption-Key-2026!Rotate-Primary",
                      "targetKeyId": "new-2026",
                      "execute": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.executed").value(false))
            .andExpect(jsonPath("$.data.items[0].status").value("WOULD_RE_ENCRYPT"));
    }

    @Test
    void reEncryptSecretsRejectsOverlongEncryptionKeyBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/config/secrets/re-encryption")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sourceEncryptionKey": "%s",
                      "sourceKeyId": "old-2026",
                      "targetEncryptionKey": "New-Encryption-Key-2026!Rotate-Primary",
                      "targetKeyId": "new-2026",
                      "execute": false
                    }
                    """.formatted("x".repeat(4097))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Request validation failed"));

        Mockito.verify(secretReEncryptionService, Mockito.never())
            .reEncrypt(org.mockito.ArgumentMatchers.any(SecretReEncryptionRequest.class));
    }

    @Test
    void reEncryptSecretsRejectsOverlongConfirmationTextBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/config/secrets/re-encryption")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sourceEncryptionKey": "Old-Encryption-Key-2026!Rotate-Primary",
                      "sourceKeyId": "old-2026",
                      "targetEncryptionKey": "New-Encryption-Key-2026!Rotate-Primary",
                      "targetKeyId": "new-2026",
                      "execute": true,
                      "confirmText": "%s"
                    }
                    """.formatted("x".repeat(33))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Request validation failed"));

        Mockito.verify(secretReEncryptionService, Mockito.never())
            .reEncrypt(org.mockito.ArgumentMatchers.any(SecretReEncryptionRequest.class));
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
            "2026-06-06 01:30:00",
            "configured"
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
            6,
            700,
            4,
            450,
            BigDecimal.valueOf(0.50),
            BigDecimal.valueOf(1.50),
            "2026-06-06 01:30:00",
            "configured"
        );
    }

    private ServiceIntegrationConfigDto mysqlDto() {
        return new ServiceIntegrationConfigDto(
            "MYSQL",
            "configured",
            "jdbc:mysql://localhost:3306/repoguard",
            "root",
            "****1234",
            "repoguard",
            null,
            null,
            "2026-06-06 01:30:00",
            "configured"
        );
    }

    private ServiceIntegrationConfigDto rabbitMqDto() {
        return new ServiceIntegrationConfigDto(
            "RABBITMQ",
            "configured",
            "amqp://localhost:5672",
            "repoguard",
            "****5678",
            "/",
            null,
            null,
            "2026-06-06 01:30:00",
            "configured"
        );
    }

    private SystemSettingsDto systemSettingsDto() {
        return new SystemSettingsDto(
            new BaseSettingsDto("RepoGuard Agent", "中文", "Asia/Shanghai", 90),
            new ReviewPolicySettingsDto(800, 60, 1, true, true),
            new NotificationSettingsDto(true, true, true, "ops@repoguard.dev"),
            new SecuritySettingsDto(true, true, false, 30),
            List.of(new SettingLogDto("2026-06-09 12:00:00", "admin", "更新系统设置", "成功"))
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
            "Java",
            "*.java",
            "medium",
            "enabled",
            3,
            "88%",
            "2026-06-09 12:00:00",
            "检测 catch Exception 等过宽异常捕获。"
        );
    }
}
