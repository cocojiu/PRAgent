package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.config.ReviewPolicySettings;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DashboardStatusMapperTest {

    private final DashboardStatusMapper mapper = new DashboardStatusMapper();

    @Test
    void mapsReviewTaskStatusesToDisplayText() {
        assertThat(mapper.reviewTaskStatusText("COMPLETED")).isEqualTo("已完成");
        assertThat(mapper.reviewTaskStatusText("reviewing")).isEqualTo("审查中");
        assertThat(mapper.reviewTaskStatusText("PUBLISH_FAILED")).isEqualTo("发布失败");
        assertThat(mapper.reviewTaskStatusText("EXECUTION_TIMEOUT")).isEqualTo("执行超时");
        assertThat(mapper.reviewTaskStatusText("REQUEUE_PENDING")).isEqualTo("重入队中");
        assertThat(mapper.reviewTaskStatusText("PENDING_HUMAN_REVIEW")).isEqualTo("待人工复核");
        assertThat(mapper.reviewTaskStatusText("CUSTOM")).isEqualTo("CUSTOM");
    }

    @Test
    void mapsGithubHealthFromRuntimeSettings() {
        assertThat(mapper.githubHealth(githubSettings("CONFIGURED", "ghp_test"))).isEqualTo("正常");
        assertThat(mapper.githubHealth(githubSettings("FAILED", "ghp_test"))).isEqualTo("异常");
        assertThat(mapper.githubHealth(githubSettings("CONFIGURED", null))).isEqualTo("未接入");
        assertThat(mapper.githubHealth(null)).isEqualTo("未接入");
    }

    @Test
    void mapsLlmHealthFromPolicySettings() {
        assertThat(mapper.llmHealth(reviewPolicySettings(true, "sk-test"))).isEqualTo("正常");
        assertThat(mapper.llmHealth(reviewPolicySettings(false, "sk-test"))).isEqualTo("已禁用");
        assertThat(mapper.llmHealth(reviewPolicySettings(true, null))).isEqualTo("未接入");
        assertThat(mapper.llmHealth(ReviewPolicySettings.empty())).isEqualTo("未接入");
        assertThat(mapper.llmHealth(null)).isEqualTo("未接入");
    }

    @Test
    void mapsRabbitMqHealthFromChannelState() {
        assertThat(mapper.rabbitMqHealth(true)).isEqualTo("正常");
        assertThat(mapper.rabbitMqHealth(false)).isEqualTo("异常");
        assertThat(mapper.rabbitMqHealth(null)).isEqualTo("异常");
    }

    private GithubIntegrationSettings githubSettings(String status, String token) {
        return new GithubIntegrationSettings("GITHUB", status, "https://api.github.com", token, null, "octocat", "api", 1L);
    }

    private ReviewPolicySettings reviewPolicySettings(boolean enabled, String apiKey) {
        return new ReviewPolicySettings(
            true,
            enabled,
            "dashscope",
            "qwen-plus",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            apiKey,
            60,
            BigDecimal.valueOf(0.20),
            4096,
            true,
            1,
            6,
            700,
            4,
            450,
            BigDecimal.valueOf(0.5),
            BigDecimal.valueOf(1.5)
        );
    }
}
