package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.StringUtils;

/** Strict and bounded parser for the repository-owned policy file. */
public final class RepositoryPolicyParser {

    public static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_LIST_ENTRIES = 128;
    private static final int MAX_RULES = 256;
    private static final int MAX_SUPPRESSIONS = 128;
    private static final int MAX_TEXT = 512;
    private static final int MAX_TOKEN_BUDGET = 128_000;
    private static final BigDecimal MAX_COST_BUDGET = new BigDecimal("1000.00");
    private static final Set<String> ROOT_FIELDS = Set.of(
        "schemaVersion", "include", "exclude", "includePatterns", "excludePatterns",
        "rules", "llm", "publication", "suppressions"
    );
    private static final Set<String> RULE_FIELDS = Set.of("enabled", "severity", "enforcement", "enforcementMode");
    private static final Set<String> LLM_FIELDS = Set.of("enabled", "tokenBudget", "maxTokens", "costBudget", "maxCost");
    private static final Set<String> PUBLICATION_FIELDS = Set.of("commentMode", "checkMode");
    private static final Set<String> SUPPRESSION_FIELDS = Set.of("ruleId", "fileGlob", "symbol", "reason", "expiresAt");
    private static final Set<String> SEVERITIES = Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> COMMENT_MODES = Set.of("OFF", "SUMMARY", "INLINE");
    private static final Set<String> CHECK_MODES = Set.of("OFF", "NEUTRAL", "BLOCKING");

    private final YAMLMapper yamlMapper;
    private final Set<String> knownRuleIds;

    public RepositoryPolicyParser() {
        this(Set.of());
    }

    public RepositoryPolicyParser(Set<String> knownRuleIds) {
        yamlMapper = YAMLMapper.builder(new YAMLFactory()).findAndAddModules().build();
        this.knownRuleIds = normalizeRuleIds(knownRuleIds);
    }

    public RepositoryPolicyDocument parse(String content) {
        if (!StringUtils.hasText(content)) {
            return RepositoryPolicyDocument.empty();
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw invalid("repository policy exceeds " + MAX_BYTES + " bytes");
        }
        try {
            JsonNode root = yamlMapper.readTree(bytes);
            if (root == null || root.isNull()) {
                return RepositoryPolicyDocument.empty();
            }
            requireObject(root, "repository policy root");
            rejectUnknown(root, ROOT_FIELDS, "repository policy field");
            int schemaVersion = positiveInt(root.get("schemaVersion"), 1, "schemaVersion");
            List<String> include = patterns(root, "include", "includePatterns", false);
            List<String> exclude = patterns(root, "exclude", "excludePatterns", false);
            Map<String, RepositoryPolicyDocument.RuleOverride> rules = rules(root.get("rules"));
            RepositoryPolicyDocument.LlmOverride llm = llm(root.get("llm"));
            RepositoryPolicyDocument.PublicationOverride publication = publication(root.get("publication"));
            List<RepositoryPolicyDocument.SuppressionReference> suppressions = suppressions(root.get("suppressions"));
            return new RepositoryPolicyDocument(schemaVersion, include, exclude, rules, llm, publication, suppressions);
        } catch (RepositoryPolicyException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid("repository policy YAML is malformed", ex);
        }
    }

    public Optional<RepositoryPolicyDocument> parseOptional(String content) {
        return StringUtils.hasText(content) ? Optional.of(parse(content)) : Optional.empty();
    }

    private Map<String, RepositoryPolicyDocument.RuleOverride> rules(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        requireObject(node, "rules");
        if (node.size() > MAX_RULES) {
            throw invalid("repository policy has too many rules");
        }
        Map<String, RepositoryPolicyDocument.RuleOverride> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String id = normalizeText(entry.getKey(), "rule id", 96).toUpperCase(Locale.ROOT);
            if (!knownRuleIds.isEmpty() && !knownRuleIds.contains(id)) {
                throw invalid("repository policy references unknown rule: " + id);
            }
            JsonNode value = entry.getValue();
            requireObject(value, "rule " + id);
            rejectUnknown(value, RULE_FIELDS, "rule " + id + " field");
            Boolean enabled = optionalBoolean(value.get("enabled"), "rule " + id + ".enabled");
            String severity = optionalEnum(value.get("severity"), SEVERITIES, "rule " + id + ".severity");
            String rawEnforcement = firstText(value, "enforcement", "enforcementMode");
            EnforcementMode enforcement = rawEnforcement == null ? null : EnforcementMode.from(rawEnforcement);
            result.put(id, new RepositoryPolicyDocument.RuleOverride(enabled, severity, enforcement));
        }
        return Map.copyOf(result);
    }

    private RepositoryPolicyDocument.LlmOverride llm(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        requireObject(node, "llm");
        rejectUnknown(node, LLM_FIELDS, "llm field");
        Boolean enabled = optionalBoolean(node.get("enabled"), "llm.enabled");
        Integer tokenBudget = optionalInt(firstNode(node, "tokenBudget", "maxTokens"), 1, MAX_TOKEN_BUDGET, "llm.tokenBudget");
        BigDecimal costBudget = optionalDecimal(firstNode(node, "costBudget", "maxCost"), MAX_COST_BUDGET, "llm.costBudget");
        return new RepositoryPolicyDocument.LlmOverride(enabled, tokenBudget, costBudget);
    }

    private RepositoryPolicyDocument.PublicationOverride publication(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        requireObject(node, "publication");
        rejectUnknown(node, PUBLICATION_FIELDS, "publication field");
        String commentMode = optionalEnum(node.get("commentMode"), COMMENT_MODES, "publication.commentMode");
        String checkMode = optionalEnum(node.get("checkMode"), CHECK_MODES, "publication.checkMode");
        return new RepositoryPolicyDocument.PublicationOverride(commentMode, checkMode);
    }

    private List<RepositoryPolicyDocument.SuppressionReference> suppressions(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_SUPPRESSIONS) {
            throw invalid("repository policy suppressions must be a bounded array");
        }
        List<RepositoryPolicyDocument.SuppressionReference> result = new ArrayList<>();
        for (JsonNode item : node) {
            requireObject(item, "suppression");
            rejectUnknown(item, SUPPRESSION_FIELDS, "suppression field");
            String ruleId = normalizeText(item.path("ruleId").asText(null), "suppression.ruleId", 96)
                .toUpperCase(Locale.ROOT);
            if (!knownRuleIds.isEmpty() && !knownRuleIds.contains(ruleId)) {
                throw invalid("suppression references unknown rule: " + ruleId);
            }
            String fileGlob = optionalText(item.get("fileGlob"), "suppression.fileGlob", 256);
            String symbol = optionalText(item.get("symbol"), "suppression.symbol", 256);
            if (!StringUtils.hasText(fileGlob) && !StringUtils.hasText(symbol)) {
                throw invalid("suppression must include fileGlob or symbol");
            }
            if (StringUtils.hasText(fileGlob)) {
                validateSuppressionGlob(fileGlob);
            }
            String reason = normalizeText(item.path("reason").asText(null), "suppression.reason", MAX_TEXT);
            String expiry = normalizeText(item.path("expiresAt").asText(null), "suppression.expiresAt", 64);
            OffsetDateTime expiresAt = parseExpiry(expiry);
            result.add(new RepositoryPolicyDocument.SuppressionReference(ruleId, fileGlob, symbol, reason, expiresAt));
        }
        return List.copyOf(result);
    }

    private List<String> patterns(JsonNode root, String primary, String alias, boolean suppression) {
        JsonNode node = root.get(primary);
        if (node != null && root.get(alias) != null) {
            throw invalid("repository policy cannot define both " + primary + " and " + alias);
        }
        if (node == null) {
            node = root.get(alias);
        }
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_LIST_ENTRIES) {
            throw invalid("repository policy " + primary + " must be a bounded array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = normalizeText(item.asText(null), primary + " pattern", 256).replace('\\', '/');
            if (value.startsWith("/") || value.contains("../") || value.contains("/..") || value.equals("..")) {
                throw invalid("repository policy " + primary + " contains an unsafe path pattern");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private void validateSuppressionGlob(String value) {
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.contains("/..")
            || normalized.equals("*") || normalized.equals("**") || normalized.chars().filter(ch -> ch == '*').count() > 8) {
            throw invalid("suppression.fileGlob is too broad or unsafe");
        }
    }

    private OffsetDateTime parseExpiry(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            try {
                return java.time.LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                throw invalid("suppression.expiresAt must be an ISO-8601 timestamp", ex);
            }
        }
    }

    private String firstText(JsonNode node, String... names) {
        JsonNode value = firstNode(node, names);
        return value == null || value.isNull() ? null : normalizeText(value.asText(null), names[0], 64);
    }

    private JsonNode firstNode(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private Boolean optionalBoolean(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw invalid(field + " must be boolean");
        }
        return node.booleanValue();
    }

    private Integer optionalInt(JsonNode node, int min, int max, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || node.intValue() < min || node.intValue() > max) {
            throw invalid(field + " must be between " + min + " and " + max);
        }
        return node.intValue();
    }

    private int positiveInt(JsonNode node, int fallback, String field) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        Integer value = optionalInt(node, 1, 10, field);
        return value == null ? fallback : value;
    }

    private BigDecimal optionalDecimal(JsonNode node, BigDecimal max, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw invalid(field + " must be numeric");
        }
        BigDecimal value = node.decimalValue();
        if (value.signum() <= 0 || value.compareTo(max) > 0) {
            throw invalid(field + " must be greater than 0 and at most " + max);
        }
        return value;
    }

    private String optionalEnum(JsonNode node, Set<String> allowed, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = normalizeText(node.asText(null), field, 32).toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw invalid(field + " has unsupported value: " + value);
        }
        return value;
    }

    private String optionalText(JsonNode node, String field, int max) {
        if (node == null || node.isNull()) {
            return null;
        }
        return normalizeText(node.asText(null), field, max);
    }

    private String normalizeText(String value, String field, int max) {
        if (!StringUtils.hasText(value)) {
            throw invalid(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw invalid(field + " exceeds " + max + " characters");
        }
        return normalized;
    }

    private void requireObject(JsonNode node, String field) {
        if (!node.isObject()) {
            throw invalid(field + " must be an object");
        }
    }

    private void rejectUnknown(JsonNode node, Set<String> allowed, String field) {
        Set<String> unknown = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw invalid(field + " contains unknown field(s): " + unknown);
        }
    }

    private Set<String> normalizeRuleIds(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        values.stream().filter(StringUtils::hasText).map(value -> value.trim().toUpperCase(Locale.ROOT)).forEach(normalized::add);
        return Set.copyOf(normalized);
    }

    private RepositoryPolicyException invalid(String message) {
        return new RepositoryPolicyException(message);
    }

    private RepositoryPolicyException invalid(String message, Throwable cause) {
        return new RepositoryPolicyException(message, cause);
    }

    public static final class RepositoryPolicyException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        RepositoryPolicyException(String message) {
            super(message);
        }

        RepositoryPolicyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
