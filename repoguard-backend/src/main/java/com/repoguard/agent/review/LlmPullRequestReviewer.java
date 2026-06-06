package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class LlmPullRequestReviewer implements PullRequestReviewer {

    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final RuleBasedPullRequestReviewer ruleBasedReviewer;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final SecretCryptoService secretCryptoService;

    public LlmPullRequestReviewer(
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        SecretCryptoService secretCryptoService
    ) {
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
        this.ruleBasedReviewer = ruleBasedReviewer;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
        this.secretCryptoService = secretCryptoService;
    }

    @Override
    public ReviewResult review(ReviewTask task, GithubPullRequestDiff diff) {
        ReviewPolicyConfig config = reviewPolicyConfigMapper.selectById(1L);
        if (!isLlmReady(config)) {
            return fallbackReview(diff, "LLM config is incomplete");
        }

        try {
            String content = callLlm(config, task, diff);
            return parseLlmResult(content);
        } catch (RuntimeException ex) {
            if (Boolean.TRUE.equals(config.getFallbackToRules())) {
                return fallbackReview(diff, ex.getMessage());
            }
            throw ex;
        }
    }

    private ReviewResult fallbackReview(GithubPullRequestDiff diff, String reason) {
        ReviewResult fallback = ruleBasedReviewer.review(diff);
        return ReviewResult.fallback(fallback.riskLevel(), normalizeReason(reason), fallback.findings());
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "LLM review unavailable";
        }
        return reason.replaceAll("\\s+", " ").trim();
    }

    private boolean isLlmReady(ReviewPolicyConfig config) {
        return config != null
            && Boolean.TRUE.equals(config.getLlmEnabled())
            && StringUtils.hasText(config.getBaseUrl())
            && StringUtils.hasText(secretCryptoService.decrypt(config.getApiKeyValue()))
            && StringUtils.hasText(config.getModelName())
            && !"mock".equalsIgnoreCase(config.getLlmProvider());
    }

    private String callLlm(ReviewPolicyConfig config, ReviewTask task, GithubPullRequestDiff diff) {
        RestClient restClient = restClientBuilder
            .baseUrl(config.getBaseUrl().trim())
            .requestFactory(requestFactory(config.getTimeoutSeconds()))
            .build();
        String apiKey = secretCryptoService.decrypt(config.getApiKeyValue());

        Map<String, Object> payload = Map.of(
            "model", config.getModelName(),
            "temperature", config.getTemperature(),
            "max_tokens", config.getMaxTokens(),
            "messages", List.of(
                Map.of("role", "system", "content", "你是资深代码审查助手，只输出严格 JSON。"),
                Map.of("role", "user", "content", buildPrompt(task, diff))
            )
        );

        String response = restClient.post()
            .uri("/chat/completions")
            .header("Authorization", "Bearer " + apiKey.trim())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(String.class);
        return extractMessageContent(response);
    }

    private String extractMessageContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response == null ? "" : response);
            return root.at("/choices/0/message/content").asText("");
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse LLM HTTP response", ex);
        }
    }

    private SimpleClientHttpRequestFactory requestFactory(Integer timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds == null ? 60 : timeoutSeconds));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private String buildPrompt(ReviewTask task, GithubPullRequestDiff diff) {
        return """
            请审查下面的 GitHub PR diff，并返回 JSON：
            {
              "riskLevel": "INFO|LOW|MEDIUM|HIGH",
              "findings": [
                {
                  "severity": "LOW|MEDIUM|HIGH",
                  "filePath": "文件路径",
                  "lineNumber": 变更后的行号或 null,
                  "message": "问题描述",
                  "recommendation": "修复建议"
                }
              ]
            }
            PR: %s/%s#%d
            标题：%s
            Diff:
            %s
            """.formatted(diff.owner(), diff.repository(), diff.prNumber(), task.getTitle(), compactDiff(diff));
    }

    private String compactDiff(GithubPullRequestDiff diff) {
        StringBuilder builder = new StringBuilder();
        for (GithubChangedFile file : diff.files()) {
            builder.append("\n--- ").append(file.filename()).append('\n');
            if (file.patch() != null) {
                builder.append(file.patch(), 0, Math.min(file.patch().length(), 6000)).append('\n');
            }
            if (builder.length() > 20000) {
                builder.append("\n[diff truncated]\n");
                break;
            }
        }
        return builder.toString();
    }

    private ReviewResult parseLlmResult(String content) {
        try {
            JsonNode root = objectMapper.readTree(stripJsonFence(content));
            String riskLevel = root.path("riskLevel").asText("INFO").toUpperCase();
            List<ReviewFindingResult> findings = new ArrayList<>();
            for (JsonNode finding : root.path("findings")) {
                findings.add(new ReviewFindingResult(
                    finding.path("severity").asText("LOW").toUpperCase(),
                    "LLM",
                    null,
                    finding.path("filePath").asText("unknown"),
                    finding.path("lineNumber").isNumber() ? finding.path("lineNumber").asInt() : null,
                    finding.path("message").asText("LLM 审查发现潜在问题"),
                    finding.path("recommendation").asText("请结合上下文确认并修复。")
                ));
            }
            return ReviewResult.completed(riskLevel, findings);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse LLM review result", ex);
        }
    }

    private String stripJsonFence(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        return trimmed;
    }
}
