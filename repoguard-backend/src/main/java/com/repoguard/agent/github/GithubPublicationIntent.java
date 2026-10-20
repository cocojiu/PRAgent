package com.repoguard.agent.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stable correlation evidence retained in the existing publication diagnostic message. */
public final class GithubPublicationIntent {
    public static final String CLOSED_PREFIX = "<!-- repoguard-resolution:closed:v1 -->\n";
    private static final Pattern MARKER = Pattern.compile("<!-- repoguard-publication:[0-9a-f-]{36}:[0-9a-f]{64}:[0-9a-f]{64} -->");
    private GithubPublicationIntent() { }
    public static String create(String commit, String body) {
        return "<!-- repoguard-publication:" + UUID.randomUUID() + ":" + hash(commit) + ":" + hash(body) + " -->";
    }
    public static String marker(String message) {
        var matcher = MARKER.matcher(message == null ? "" : message);
        return matcher.find() ? matcher.group() : null;
    }
    public static boolean matchesCommit(String marker, String commit) {
        return marker != null && marker.contains(":" + hash(commit) + ":");
    }
    public static String message(String marker, String reason) {
        String value = (marker == null ? "" : marker + "\n") + reason;
        return value.substring(0, Math.min(value.length(), 1024));
    }
    /** Preserve the pre-existing quarantine status on disk so older safe readers still block POST. */
    public static String status(String storedStatus, String message) {
        return "outcome_unknown".equals(storedStatus) && message != null && message.startsWith(CLOSED_PREFIX)
            ? "closed" : storedStatus;
    }
    public static boolean pending(String storedStatus, String message) {
        return "outcome_unknown".equals(status(storedStatus, message));
    }
    public static String displayMessage(String message) {
        if (message == null) return null;
        String display = message.startsWith(CLOSED_PREFIX) ? message.substring(CLOSED_PREFIX.length()) : message;
        String intent = marker(display);
        return (intent == null ? display : display.replace(intent, "")).strip();
    }
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
