package com.repoguard.agent.review;

import com.repoguard.agent.entity.ReviewFinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds the versioned identity used when findings are compared across attempts.
 * Location lines are deliberately excluded: a harmless edit before a finding must
 * not turn the same issue into a new finding.
 */
public final class ReviewFindingIdentity {

    public static final String VERSION = "finding-diff-v1";
    private static final Pattern LINE_REFERENCE = Pattern.compile(
        "(?i)\\b(?:line|lines|行)\\s*[:#]?\\s*\\d+(?:\\s*[-–]\\s*\\d+)?"
    );
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ReviewFindingIdentity() {
    }

    public static String fingerprint(Long taskId, ReviewFinding finding) {
        if (finding == null) {
            return null;
        }
        String material = String.join("\u001f",
            identityPrefix(taskId, finding),
            normalizePath(finding.getFilePath())
        );
        return sha256(material);
    }

    /**
     * A coarse key used only to distinguish a location move/cross-file mismatch
     * from a genuinely new semantic finding. It is never persisted as identity.
     */
    public static String locationIndependentKey(Long taskId, ReviewFinding finding) {
        if (finding == null) {
            return null;
        }
        return sha256(identityPrefix(taskId, finding));
    }

    private static String identityPrefix(Long taskId, ReviewFinding finding) {
        return String.join("\u001f",
            normalize(taskId),
            normalize(finding.getSource()),
            normalize(finding.getLlmProvider()),
            normalize(finding.getRuleId()),
            normalize(finding.getIssueType()),
            normalize(finding.getMethodName()),
            normalize(finding.getAnchorType()),
            semanticSummary(finding.getMessage())
        );
    }

    private static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String semanticSummary(String value) {
        if (value == null) {
            return "";
        }
        String withoutLine = LINE_REFERENCE.matcher(value).replaceAll("line");
        return WHITESPACE.matcher(withoutLine.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    public static String normalizePath(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return WHITESPACE.matcher(normalized).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private static String normalize(Object value) {
        return value == null
            ? ""
            : WHITESPACE.matcher(String.valueOf(value).trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
}
