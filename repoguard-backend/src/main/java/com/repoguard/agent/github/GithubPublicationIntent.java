package com.repoguard.agent.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stable correlation evidence retained in the existing publication diagnostic message. */
public final class GithubPublicationIntent {
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
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
