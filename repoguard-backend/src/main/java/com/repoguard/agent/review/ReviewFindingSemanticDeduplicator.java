package com.repoguard.agent.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ReviewFindingSemanticDeduplicator {

    List<ReviewFindingResult> deduplicate(List<ReviewFindingResult> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        Map<String, ReviewFindingResult> unique = new LinkedHashMap<>();
        for (ReviewFindingResult finding : findings) {
            if (finding == null) {
                continue;
            }
            String key = semanticKey(finding);
            unique.merge(key, finding, this::merge);
        }
        return List.copyOf(unique.values());
    }

    private ReviewFindingResult merge(ReviewFindingResult first, ReviewFindingResult second) {
        ReviewFindingResult stronger = stronger(first, second);
        boolean consensus = differentEvidenceSources(first, second);
        String mergedPolicyReason = mergeText(first.policyReason(), second.policyReason());
        if (consensus) {
            mergedPolicyReason = mergeText(mergedPolicyReason, "server_consensus_confidence_boost");
        }
        return new ReviewFindingResult(
            stronger.severity(),
            mergeSource(first.source(), second.source()),
            mergeText(first.ruleId(), second.ruleId()),
            stronger.filePath(),
            stronger.lineNumber(),
            stronger.message(),
            mergeText(first.recommendation(), second.recommendation()),
            consensus ? "HIGH" : higherConfidence(first.confidence(), second.confidence()),
            mergeText(first.evidence(), second.evidence()),
            mergeText(first.impact(), second.impact()),
            mergeText(first.fixExample(), second.fixExample()),
            first.isBlocking() || second.isBlocking(),
            mergeText(first.reviewDimension(), second.reviewDimension()),
            strongerEnforcement(first.enforcementMode(), second.enforcementMode()),
            mergedPolicyReason,
            preferredIssueType(stronger, first == stronger ? second : first),
            mergeText(first.preconditions(), second.preconditions()),
            mergeRelatedFiles(first.relatedFiles(), second.relatedFiles()),
            first.blockingCandidate() || second.blockingCandidate(),
            preferredVerificationStatus(first.verificationStatus(), second.verificationStatus())
        );
    }

    private boolean differentEvidenceSources(ReviewFindingResult first, ReviewFindingResult second) {
        String left = normalize(first.source()).toUpperCase(Locale.ROOT);
        String right = normalize(second.source()).toUpperCase(Locale.ROOT);
        return left.contains("LLM") && right.contains("RULE") || left.contains("RULE") && right.contains("LLM");
    }

    private String preferredIssueType(ReviewFindingResult preferred, ReviewFindingResult fallback) {
        if (StringUtils.hasText(preferred.issueType()) && !"GENERAL".equalsIgnoreCase(preferred.issueType())) {
            return preferred.issueType();
        }
        return fallback.issueType();
    }

    private List<String> mergeRelatedFiles(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return List.copyOf(merged);
    }

    private String preferredVerificationStatus(String first, String second) {
        return verificationRank(first) >= verificationRank(second) ? first : second;
    }

    private int verificationRank(String value) {
        if (LlmVerificationStatus.VERIFIED.name().equalsIgnoreCase(value)) {
            return 5;
        }
        if (LlmVerificationStatus.PENDING.name().equalsIgnoreCase(value)) {
            return 4;
        }
        if (LlmVerificationStatus.REJECTED.name().equalsIgnoreCase(value)
            || LlmVerificationStatus.UNCERTAIN.name().equalsIgnoreCase(value)) {
            return 3;
        }
        if (LlmVerificationStatus.UNAVAILABLE.name().equalsIgnoreCase(value)) {
            return 2;
        }
        return 1;
    }

    private ReviewFindingResult stronger(ReviewFindingResult first, ReviewFindingResult second) {
        int severityCompare = Integer.compare(severityRank(first.severity()), severityRank(second.severity()));
        if (severityCompare != 0) {
            return severityCompare > 0 ? first : second;
        }
        return confidenceRank(first.confidence()) >= confidenceRank(second.confidence()) ? first : second;
    }

    private String semanticKey(ReviewFindingResult finding) {
        String file = normalize(finding.filePath()).replace('\\', '/');
        String location = finding.lineNumber() == null || finding.lineNumber() < 1
            ? "file"
            : "line:" + finding.lineNumber();
        return file + "|" + issueFamily(finding) + "|" + location;
    }

    private String issueFamily(ReviewFindingResult finding) {
        Set<String> ruleIds = splitValues(finding.ruleId());
        String text = normalize(
            String.join(
                " ",
                nullToEmpty(finding.reviewDimension()),
                nullToEmpty(finding.message()),
                nullToEmpty(finding.evidence())
            )
        );
        String textFamily = issueFamilyFromText(text);
        if (textFamily != null) {
            return textFamily;
        }
        String ruleFamily = issueFamilyFromRules(ruleIds);
        if (ruleFamily != null) {
            return ruleFamily;
        }
        if (!ruleIds.isEmpty()) {
            return ruleIds.stream().sorted().findFirst().orElse("general");
        }
        return "general:" + stableTerms(text);
    }

    private String issueFamilyFromRules(Set<String> ruleIds) {
        for (String ruleId : ruleIds) {
            String family = switch (ruleId) {
                case "RG-SECRET-001" -> "security:secret";
                case "RG-AUTH-001" -> "security:authorization";
                case "RG-LOG-001" -> "logging:sensitive";
                case "RG-DB-002", "RG-DB-003" -> "database:migration";
                case "RG-MQ-001" -> "messaging:publish";
                case "RG-GH-001" -> "github:comment";
                case "RG-EXT-001" -> "external:call";
                case "RG-STATE-001" -> "task:state";
                case "RG-API-001" -> "api:test-coverage";
                case "RG-JAVA-001" -> "java:broad-catch";
                case "RG-JAVA-002" -> "logging:stdout";
                case "RG-JAVA-003" -> "reliability:fixed-sleep";
                case "RG-JAVA-004" -> "maintainability:todo";
                default -> null;
            };
            if (family != null) {
                return family;
            }
        }
        return null;
    }

    private String issueFamilyFromText(String text) {
        if (containsAny(text, "sensitive log", "logging secret", "logged token", "log token")) {
            return "logging:sensitive";
        }
        if (containsAny(text, "secret", "token", "password", "credential", "api key", "apikey")) {
            return "security:secret";
        }
        if (containsAny(text, "authorization", "permission", "access control", "role")) {
            return "security:authorization";
        }
        if (containsAny(text, "migration", "drop table", "drop column", "not null")) {
            return "database:migration";
        }
        if (containsAny(text, "rabbit", "message publish", "outbox")) {
            return "messaging:publish";
        }
        if (containsAny(text, "github", "comment", "publication")) {
            return "github:comment";
        }
        if (containsAny(text, "external call", "http client", "restclient", "webclient", "feign")) {
            return "external:call";
        }
        if (containsAny(text, "task status", "state machine", "setstatus")) {
            return "task:state";
        }
        if (containsAny(text, "test coverage", "missing test", "controller test")) {
            return "api:test-coverage";
        }
        if (containsAny(text, "broad catch", "catch exception", "catch throwable")) {
            return "java:broad-catch";
        }
        if (containsAny(text, "system.out", "standard output", "stdout")) {
            return "logging:stdout";
        }
        if (containsAny(text, "thread.sleep", "fixed sleep")) {
            return "reliability:fixed-sleep";
        }
        if (containsAny(text, "todo", "fixme")) {
            return "maintainability:todo";
        }
        return null;
    }

    private String stableTerms(String text) {
        List<String> terms = new ArrayList<>();
        for (String token : text.replaceAll("[^a-z0-9_]+", " ").split("\\s+")) {
            if (token.length() < 4 || Set.of("this", "that", "with", "from", "finding").contains(token)) {
                continue;
            }
            terms.add(token);
            if (terms.size() == 4) {
                break;
            }
        }
        return terms.isEmpty() ? "unknown" : String.join(",", terms);
    }

    private String mergeSource(String first, String second) {
        Set<String> sources = new LinkedHashSet<>();
        addValues(sources, first);
        addValues(sources, second);
        if (sources.contains("LLM") && sources.contains("RULE")) {
            return "LLM+RULE";
        }
        return String.join("+", sources);
    }

    private String strongerEnforcement(String first, String second) {
        return enforcementRank(first) >= enforcementRank(second) ? first : second;
    }

    private int enforcementRank(String value) {
        if ("BLOCK".equalsIgnoreCase(value)) {
            return 3;
        }
        if ("COMMENT".equalsIgnoreCase(value)) {
            return 2;
        }
        return 1;
    }

    private String higherConfidence(String first, String second) {
        return confidenceRank(first) >= confidenceRank(second) ? first : second;
    }

    private int confidenceRank(String value) {
        if ("HIGH".equalsIgnoreCase(value)) {
            return 3;
        }
        if ("MEDIUM".equalsIgnoreCase(value)) {
            return 2;
        }
        return 1;
    }

    private int severityRank(String value) {
        return switch (normalize(value).toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private String mergeText(String first, String second) {
        String left = trimToNull(first);
        String right = trimToNull(second);
        if (left == null) {
            return right;
        }
        if (right == null || left.equalsIgnoreCase(right)) {
            return left;
        }
        return left + " / " + right;
    }

    private Set<String> splitValues(String value) {
        Set<String> values = new LinkedHashSet<>();
        addValues(values, value);
        return values;
    }

    private void addValues(Set<String> values, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        for (String part : value.toUpperCase(Locale.ROOT).split("[+/]")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
