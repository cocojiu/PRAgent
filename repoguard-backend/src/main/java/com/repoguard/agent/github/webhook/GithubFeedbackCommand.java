package com.repoguard.agent.github.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.common.SensitiveTextSanitizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Explicit replies to published review comments only; arbitrary prose is never interpreted. */
public record GithubFeedbackCommand(String owner, String repository, int prNumber, String headSha,
    long commentId, long parentCommentId, long actorId, String actor, String feedbackStatus,
    String note, String bodyHash, LocalDateTime createdAt, Long installationId) {

    private static final Pattern COMMAND = Pattern.compile("^/repoguard (false-positive|ignore) ([^\\r\\n]{1,512})$");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    public static GithubFeedbackCommand parse(JsonNode root) {
        if (root == null || !"created".equals(root.path("action").asText())) return null;
        JsonNode comment = root.path("comment");
        JsonNode actor = comment.path("user");
        if (!"User".equals(actor.path("type").asText())
            || actor.path("id").asLong() != root.path("sender").path("id").asLong()) return null;
        String login = actor.path("login").asText("");
        String owner = root.path("repository").path("owner").path("login").asText("");
        String repository = root.path("repository").path("name").asText("");
        String sha = root.path("pull_request").path("head").path("sha").asText("");
        String body = comment.path("body").asText("").trim();
        if (body.length() > 550 || !NAME.matcher(owner).matches() || !NAME.matcher(repository).matches()
            || !NAME.matcher(login).matches() || !sha.matches("[a-fA-F0-9]{40}")) return null;
        var match = COMMAND.matcher(body);
        if (!match.matches() || match.group(2).isBlank() || comment.path("id").asLong() <= 0
            || comment.path("in_reply_to_id").asLong() <= 0 || actor.path("id").asLong() <= 0
            || root.path("pull_request").path("number").asInt() <= 0
            || !sha.equalsIgnoreCase(comment.path("commit_id").asText())) return null;
        try {
            LocalDateTime created = OffsetDateTime.parse(comment.path("created_at").asText())
                .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            Long installation = root.path("installation").path("id").asLong() > 0
                ? root.path("installation").path("id").asLong() : null;
            return new GithubFeedbackCommand(owner, repository, root.path("pull_request").path("number").asInt(),
                sha.toLowerCase(java.util.Locale.ROOT), comment.path("id").asLong(), comment.path("in_reply_to_id").asLong(),
                actor.path("id").asLong(), login, "ignore".equals(match.group(1)) ? "ignored" : "false_positive",
                SensitiveTextSanitizer.sanitize(match.group(2).trim()), hash(body), created, installation);
        } catch (RuntimeException ex) { return null; }
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
