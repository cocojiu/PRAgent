package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.security.SecretCryptoService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

abstract class AbstractWebhookNotificationAdapter implements NotificationChannelAdapter {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestClient restClient;
    private final SecretCryptoService secretCryptoService;

    AbstractWebhookNotificationAdapter(RestClient.Builder restClientBuilder, SecretCryptoService secretCryptoService) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(8000);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.secretCryptoService = secretCryptoService;
    }

    @Override
    public NotificationSendResult send(NotificationMessage message, NotificationChannelBinding binding) {
        return doPost(binding, payload(title(message), markdown(message)));
    }

    @Override
    public NotificationSendResult test(NotificationChannelBinding binding) {
        return doPost(binding, payload("RepoGuard 通知测试", "### RepoGuard 通知测试\n\n这是一条连接测试消息。\n\n时间：" + DATE_TIME_FORMATTER.format(LocalDateTime.now())));
    }

    protected abstract Object payload(String title, String markdown);

    protected NotificationSendResult doPost(NotificationChannelBinding binding, Object payload) {
        String webhookUrl = secretCryptoService.decrypt(binding.getWebhookUrlValue());
        if (!StringUtils.hasText(webhookUrl)) {
            return NotificationSendResult.failed(null, "Webhook URL is empty");
        }
        try {
            Object response = restClient.post()
                .uri(signedWebhookUrl(webhookUrl, secretCryptoService.decrypt(binding.getSecretValue())))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(Object.class);
            String responseText = response == null ? "" : truncate(response.toString(), 512);
            if (isSuccessResponse(responseText)) {
                return NotificationSendResult.success(null, responseText);
            }
            return NotificationSendResult.failed(null, responseText);
        } catch (RuntimeException ex) {
            return NotificationSendResult.failed(null, truncate(ex.getMessage(), 512));
        }
    }

    protected String signedWebhookUrl(String webhookUrl, String secret) {
        return webhookUrl;
    }

    protected boolean isSuccessResponse(String responseText) {
        String normalized = responseText == null ? "" : responseText.toLowerCase();
        return normalized.contains("errcode=0")
            || normalized.contains("\"errcode\":0")
            || normalized.contains("errmsg=ok")
            || normalized.contains("\"errmsg\":\"ok\"");
    }

    protected String markdown(NotificationMessage message) {
        return """
            ### RepoGuard 审查通知

            - 事件：%s
            - 仓库：%s/%s
            - PR：#%s %s
            - 状态：%s
            - 风险：%s
            - 问题数：%s
            - 评论回写：成功 %s，失败 %s，跳过 %s

            [查看详情](%s)
            """.formatted(
            eventText(message.eventType()),
            safe(message.organization()),
            safe(message.repository()),
            message.prNumber() == null ? "-" : message.prNumber(),
            safe(message.title()),
            safe(message.status()),
            safe(message.riskLevel()),
            message.findingCount() == null ? 0 : message.findingCount(),
            message.commentSucceededCount() == null ? 0 : message.commentSucceededCount(),
            message.commentFailedCount() == null ? 0 : message.commentFailedCount(),
            message.commentSkippedCount() == null ? 0 : message.commentSkippedCount(),
            safe(message.detailUrl())
        );
    }

    protected String title(NotificationMessage message) {
        return "RepoGuard " + eventText(message.eventType()) + " - " + safe(message.repository()) + " PR #" + (message.prNumber() == null ? "-" : message.prNumber());
    }

    private String eventText(String eventType) {
        return switch (eventType == null ? "" : eventType) {
            case "REVIEW_COMPLETED" -> "审查完成";
            case "HUMAN_REVIEW_REQUIRED" -> "待人工复核";
            case "REVIEW_FAILED" -> "审查失败";
            case "GITHUB_COMMENT_PUBLISHED" -> "GitHub 评论回写";
            default -> eventType;
        };
    }

    protected String safe(String value) {
        if (!StringUtils.hasText(value)) {
            return "-";
        }
        return value.replace("\r", " ").replace("\n", " ").trim();
    }

    protected String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    protected byte[] utf8(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    protected Map<String, Object> markdownMap(String title, String text) {
        return Map.of("title", title, "text", text);
    }
}
