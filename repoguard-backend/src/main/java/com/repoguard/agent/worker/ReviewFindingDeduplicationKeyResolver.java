package com.repoguard.agent.worker;

import com.repoguard.agent.review.ReviewFindingResult;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class ReviewFindingDeduplicationKeyResolver {

    String key(ReviewFindingResult finding) {
        String file = normalizeFilePath(finding.filePath());
        String issueType = issueType(finding);
        String scope = lineScope(finding);
        if (!issueType.startsWith("general:")) {
            return file + "|" + issueType + "|" + scope;
        }
        return file + "|" + issueType + "|" + scope + "|" + String.join(",", semanticTerms(finding));
    }

    private String normalizeFilePath(String filePath) {
        String normalized = normalizeKeyPart(filePath).replace('\\', '/');
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private String lineScope(ReviewFindingResult finding) {
        if (finding.lineNumber() == null || finding.lineNumber() < 1) {
            return "line:any";
        }
        return "line:" + (finding.lineNumber() / 10);
    }

    private String issueType(ReviewFindingResult finding) {
        String text = searchableText(finding);
        String ruleId = normalizeKeyPart(finding.ruleId());
        if (ruleId.startsWith("rg-java-002") || containsAny(text, "system.out", "stdout", "console.log", "print ")) {
            return "logging:stdout";
        }
        if (ruleId.startsWith("rg-java-003") || containsAny(text, "thread.sleep", "fixed sleep", "sleep ")) {
            return "concurrency:fixed_sleep";
        }
        if (ruleId.contains("secret") || containsAny(text, "secret", "token", "password", "credential", "apikey", "api key")) {
            return "security:secret";
        }
        if (containsAny(text, "auth", "authorization", "permission", "role", "access control")) {
            return "security:access_control";
        }
        if (containsAny(text, "sql", "migration", "drop table", "alter table", "not null", "default")) {
            return "database:schema";
        }
        if (containsAny(text, "rabbit", "message", "publish", "compensation", "retry")) {
            return "messaging:reliability";
        }
        if (containsAny(text, "http", "resttemplate", "webclient", "timeout", "retry", "external call")) {
            return "integration:external_call";
        }
        if (containsAny(text, "status", "state machine", "state transition")) {
            return "workflow:state";
        }
        return "general:" + firstStableTerm(semanticTerms(finding));
    }

    private String normalizeKeyPart(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? "" : trimmed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String searchableText(ReviewFindingResult finding) {
        return normalizeKeyPart(String.join(
            " ",
            nullToEmpty(finding.ruleId()),
            nullToEmpty(finding.reviewDimension()),
            nullToEmpty(finding.message()),
            nullToEmpty(finding.recommendation()),
            nullToEmpty(finding.evidence()),
            nullToEmpty(finding.impact())
        ));
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> semanticTerms(ReviewFindingResult finding) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : searchableText(finding).replaceAll("[^a-z0-9_./-]+", " ").split("\\s+")) {
            String normalized = normalizedTerm(token);
            if (normalized == null || normalized.length() < 3 || isStopWord(normalized)) {
                continue;
            }
            terms.add(normalized);
            if (terms.size() == 6) {
                break;
            }
        }
        if (terms.isEmpty()) {
            terms.add("general");
        }
        return terms;
    }

    private String normalizedTerm(String token) {
        String value = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return null;
        }
        if (value.contains("system.out") || value.equals("stdout") || value.equals("console.log")) {
            return "stdout";
        }
        if (value.equals("logger") || value.equals("logging") || value.equals("log")) {
            return "logging";
        }
        if (value.equals("authorization") || value.equals("permission") || value.equals("role")) {
            return "auth";
        }
        if (value.equals("credential") || value.equals("password") || value.equals("apikey")) {
            return "secret";
        }
        return value;
    }

    private boolean isStopWord(String value) {
        return Set.of(
            "the", "and", "for", "with", "this", "that", "from", "should", "could", "would",
            "use", "add", "replace", "avoid", "missing", "issue", "review", "rule", "llm",
            "src", "main", "java"
        ).contains(value);
    }

    private String firstStableTerm(Set<String> terms) {
        return terms.stream().findFirst().orElse("general");
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
