package com.repoguard.agent.github.webhook;

public record GithubWebhookResponse(
    String status,
    String message,
    Long taskId,
    Boolean existing,
    String deliveryId,
    String action
) {
    public static GithubWebhookResponse skipped(String message, String deliveryId, String action) {
        return new GithubWebhookResponse("skipped", message, null, null, deliveryId, action);
    }
}
