package com.repoguard.agent.review.codeowners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Reads operator-provisioned mapping data. Never accept this document from a recommendation request. */
public final class CodeownersMappingCatalog {
    private CodeownersMappingCatalog() { }
    public record Scope(String status, Map<String, List<Long>> owners) { }

    public static Scope resolve(byte[] document, long tenantId, String repository, ObjectMapper json) {
        if (tenantId < 1 || !validRepository(repository)) {
            return empty("INVALID_SCOPE");
        }
        if (document == null || document.length == 0) return empty("NOT_CONFIGURED");
        if (document.length > 262144) return empty("BUDGET_EXCEEDED");
        try {
            JsonNode root = json.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(document);
            if (root == null || !root.isArray() || root.size() > 200) return empty("INVALID_MAPPING");
            Map<String, List<Long>> selected = null;
            java.util.Set<String> seenScopes = new LinkedHashSet<>();
            for (JsonNode row : root) {
                JsonNode tenant = row.path("tenantId"), repo = row.path("repository"), owners = row.path("owners");
                if (!tenant.isIntegralNumber() || !tenant.canConvertToLong() || tenant.longValue() < 1
                    || !repo.isTextual() || !validRepository(repo.textValue())
                    || !owners.isObject() || owners.size() > 1000) return empty("INVALID_MAPPING");
                String canonicalRepo = repo.textValue().toLowerCase(java.util.Locale.ROOT);
                if (!seenScopes.add(tenant.longValue() + ":" + canonicalRepo)) return empty("AMBIGUOUS_MAPPING");
                Map<String, List<Long>> entries = new LinkedHashMap<>();
                for (var entry : owners.properties()) {
                    if (entry.getKey().length() > 255 || !entry.getKey().matches(
                        "@[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)?|[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                        || !entry.getValue().isArray() || entry.getValue().size() > 20) return empty("INVALID_MAPPING");
                    List<Long> users = new ArrayList<>();
                    for (JsonNode user : entry.getValue()) {
                        if (!user.isIntegralNumber() || !user.canConvertToLong() || user.longValue() < 1) return empty("INVALID_MAPPING");
                        users.add(user.longValue());
                    }
                    entries.put(entry.getKey(), List.copyOf(new LinkedHashSet<>(users)));
                }
                if (tenant.longValue() == tenantId && canonicalRepo.equals(repository.toLowerCase(java.util.Locale.ROOT))) {
                    selected = Map.copyOf(entries);
                }
            }
            return selected == null ? empty("SCOPE_NOT_CONFIGURED") : new Scope("CONFIGURED", selected);
        } catch (Exception failure) { return empty("INVALID_MAPPING"); }
    }
    private static boolean validRepository(String value) {
        return value != null && value.length() <= 255 && value.matches("[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+")
            && !value.endsWith("/.") && !value.endsWith("/..");
    }
    private static Scope empty(String status) { return new Scope(status, Map.of()); }
}
