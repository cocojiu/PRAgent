package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class MigrationContractManifestTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Path MANIFEST = MIGRATION_DIR.resolve("migration-contract.json");
    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__");
    private static final Set<String> ALLOWED_PHASES = Set.of("BASELINE", "EXPAND", "CONTRACT");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void everyMigrationResolvesToACompleteContractProfile() throws IOException {
        JsonNode root = readManifest();
        JsonNode defaults = root.required("defaults");
        int defaultThroughVersion = root.required("defaultThroughVersion").asInt();
        Map<Integer, JsonNode> explicit = explicitMigrations(root);
        Set<Integer> versions = migrationVersions();

        assertThat(root.required("formatVersion").asInt()).isEqualTo(1);
        assertThat(root.required("futureMigrationsRequireExplicitMetadata").asBoolean()).isTrue();
        assertThat(versions).contains(76, 77, 78);
        assertThat(explicit.keySet()).doesNotHaveDuplicates();

        for (int version : versions) {
            JsonNode entry = explicit.get(version);
            if (version > defaultThroughVersion) {
                assertThat(entry)
                    .as("migration V%d must have explicit expand-contract metadata", version)
                    .isNotNull();
            }
            JsonNode resolved = entry == null ? defaults : merge(defaults, entry);
            assertCompleteProfile(version, resolved);
            if (entry != null) {
                assertThat(entry.required("file").asText())
                    .as("manifest file for V%d", version)
                    .isEqualTo("V" + version + "__" + migrationSuffix(version) + ".sql");
            }
        }
    }

    @Test
    void baselinePolicyDefersConsolidationUntilExternalDatabaseEvidenceIsComplete() throws IOException {
        JsonNode policy = readManifest().required("baselinePolicy");

        assertThat(policy.required("decision").asText()).isEqualTo("DEFER_CONSOLIDATION");
        assertThat(policy.required("externalDatabaseSupported").asBoolean()).isTrue();
        assertThat(policy.required("publishedMigrationPolicy").asText()).isEqualTo("IMMUTABLE");
        assertThat(policy.required("newInstallationStrategy").asText())
            .isEqualTo("VALIDATED_SCHEMA_SNAPSHOT_THEN_BASELINE");
        assertThat(policy.required("candidateVersion").asInt())
            .isEqualTo(migrationVersions().stream().mapToInt(Integer::intValue).max().orElseThrow());
        assertThat(policy.required("requiredEvidence").isArray()).isTrue();
        assertThat(policy.required("requiredEvidence"))
            .hasSizeGreaterThanOrEqualTo(4)
            .allMatch(node -> !node.asText().isBlank());
        assertThat(policy.required("decisionEvidence").isArray()).isTrue();
        assertThat(policy.required("decisionEvidence"))
            .hasSizeGreaterThanOrEqualTo(2)
            .allMatch(node -> !node.asText().isBlank());
        assertThat(policy.required("reconsiderWhen").asText()).containsIgnoringCase("snapshot");
        assertThat(policy.required("rollbackStrategy").asText())
            .containsIgnoringCase("never rewrite or delete");
    }

    @Test
    void expandMigrationsDoNotContainDestructiveOrDataRewriteStatements() throws IOException {
        JsonNode root = readManifest();
        for (JsonNode entry : root.required("migrations")) {
            if (!"EXPAND".equals(entry.required("phase").asText())) {
                continue;
            }
            String sql = Files.readString(
                MIGRATION_DIR.resolve(entry.required("file").asText()),
                StandardCharsets.UTF_8
            ).toLowerCase(Locale.ROOT);
            assertThat(sql)
                .as("expand migration V%d must remain additive", entry.required("version").asInt())
                .doesNotContain("drop foreign key", "drop column", "drop table", "drop index", "delete ", "update ", "insert ");
        }
    }

    @Test
    void contractMigrationsDeclareMaintenanceAndDirtyDataRecovery() throws IOException {
        JsonNode root = readManifest();
        JsonNode contracts = StreamSupport.stream(root.required("migrations").spliterator(), false)
            .filter(entry -> "CONTRACT".equals(entry.required("phase").asText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("At least one contract migration is required"));

        assertThat(contracts.required("maintenanceWindowRequired").asBoolean()).isTrue();
        assertThat(contracts.required("dirtyDataProbe").asText()).isNotBlank();
        assertThat(contracts.required("interruptedRecovery").asText()).containsIgnoringCase("repair");
        assertThat(contracts.required("rollbackStrategy").asText()).containsIgnoringCase("forward");
    }

    private JsonNode readManifest() throws IOException {
        return OBJECT_MAPPER.readTree(Files.readString(MANIFEST, StandardCharsets.UTF_8));
    }

    private Map<Integer, JsonNode> explicitMigrations(JsonNode root) {
        Map<Integer, JsonNode> entries = new HashMap<>();
        for (JsonNode entry : root.required("migrations")) {
            int version = entry.required("version").asInt();
            assertThat(entries.put(version, entry))
                .as("duplicate migration metadata V%d", version)
                .isNull();
        }
        return entries;
    }

    private Set<Integer> migrationVersions() throws IOException {
        Set<Integer> versions = new HashSet<>();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                .forEach(path -> {
                    Matcher matcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
                    assertThat(matcher.find()).isTrue();
                    assertThat(versions.add(Integer.parseInt(matcher.group(1)))).isTrue();
                });
        }
        return versions;
    }

    private JsonNode merge(JsonNode defaults, JsonNode entry) {
        ObjectNode merged = defaults.deepCopy();
        merged.setAll((ObjectNode) entry);
        return merged;
    }

    private void assertCompleteProfile(int version, JsonNode profile) {
        assertThat(ALLOWED_PHASES).contains(profile.required("phase").asText());
        assertThat(profile.required("estimatedRows").asText()).isNotBlank();
        assertThat(profile.required("lockType").asText()).isNotBlank();
        assertThat(profile.required("maxDurationSeconds").asInt()).isPositive();
        assertThat(profile.required("maxDiskMegabytes").asInt()).isPositive();
        assertThat(profile.required("nMinusOneCompatible").isBoolean()).isTrue();
        assertThat(profile.required("interruptedRecovery").asText()).isNotBlank();
        assertThat(profile.required("rollbackStrategy").asText()).isNotBlank();
        assertThat(version).isPositive();
    }

    private String migrationSuffix(int version) throws IOException {
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            return files.map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith("V" + version + "__"))
                .findFirst()
                .map(name -> name.substring(("V" + version + "__").length(), name.length() - 4))
                .orElseThrow(() -> new AssertionError("Missing migration V" + version));
        }
    }
}
