package com.repoguard.agent.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Small, explicit ratchets for the continuously growing monolith. Detailed
 * domain dependency rules remain in {@link ApplicationArchitectureTest}; this
 * test makes class-size and forbidden side-channel drift visible in every run.
 */
class ArchitectureRatchetTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java").toAbsolutePath().normalize();
    private static final Path REPOSITORY_ROOT = MAIN_SOURCE_ROOT.getParent().getParent().getParent().getParent();
    private static final String RATchet_RESOURCE = "architecture-ratchet.properties";
    private static final Pattern REQUIRED_ENVIRONMENT_VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)\\}");
    private static final Pattern INTERFACE_DECLARATION = Pattern.compile(
        "\\binterface\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
    );
    private static final Pattern SCHEDULED_METHOD = Pattern.compile(
        "(?m)^\\s*@Scheduled(?:\\([^\\r\\n]*\\))?\\s*\\r?\\n"
            + "\\s*public\\s+(?:[A-Za-z_$][A-Za-z0-9_$]*\\s+)*void\\s+"
            + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\("
    );

    @Test
    void productionClassesStayBelowTheReviewedComplexityThreshold() throws IOException {
        Properties properties = loadProperties();
        int maximumLines = Integer.parseInt(properties.getProperty("maxProductionClassLines", "450"));
        List<SourceSize> sources;
        try (Stream<Path> paths = Files.walk(MAIN_SOURCE_ROOT)) {
            sources = paths
                .filter(path -> path.toString().endsWith(".java"))
                .map(ArchitectureRatchetTest::sourceSize)
                .sorted((left, right) -> Integer.compare(right.lines(), left.lines()))
                .toList();
        }

        assertThat(sources).isNotEmpty();
        SourceSize largest = sources.getFirst();
        assertThat(largest.lines())
            .as("largest production class %s (ratchet=%d lines)", largest.path(), maximumLines)
            .isLessThanOrEqualTo(maximumLines);
    }

    @Test
    void productionCodeCannotCreateUnownedUtilityOrLegacySideChannels() throws IOException {
        Properties properties = loadProperties();
        List<String> forbiddenRoots = Arrays.stream(
                properties.getProperty("forbiddenProductionPackageRoots", "").split(",")
            )
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
        List<String> violations;
        try (Stream<Path> paths = Files.walk(MAIN_SOURCE_ROOT)) {
            violations = paths
                .filter(path -> path.toString().endsWith(".java"))
                .map(MAIN_SOURCE_ROOT::relativize)
                .map(path -> path.toString().replace('\\', '/'))
                .filter(path -> forbiddenRoots.stream().anyMatch(root -> path.startsWith(root + "/")))
                .toList();
        }

        assertThat(violations)
            .as("new production code must live in an owned domain or technical boundary")
            .isEmpty();
    }

    @Test
    void totalProductionComplexityStaysWithinTheReviewedBudget() throws IOException {
        Properties properties = loadProperties();
        List<Path> sources = productionSources();
        long productionLines = sources.stream()
            .mapToLong(path -> sourceSize(path).lines())
            .sum();
        int productionClassCount = sources.size();

        assertThat(productionLines)
            .as("production Java line budget")
            .isLessThanOrEqualTo(Long.parseLong(properties.getProperty("maxProductionJavaLines", "70036")));
        assertThat(productionClassCount)
            .as("production Java class budget")
            .isLessThanOrEqualTo(Integer.parseInt(properties.getProperty("maxProductionClassCount", "941")));
    }

    @Test
    void personalRuntimeAndWorkflowBudgetsStayExplicit() throws IOException {
        Properties properties = loadProperties();
        int workflowCount = trackedFiles(".github/workflows").size();
        int requiredEnvironmentVariables = requiredPersonalEnvironmentVariables().size();

        assertThat(workflowCount)
            .as("tracked workflow budget")
            .isLessThanOrEqualTo(Integer.parseInt(properties.getProperty("maxTrackedWorkflowCount", "15")));
        assertThat(requiredEnvironmentVariables)
            .as("required environment variables in the default personal profile")
            .isLessThanOrEqualTo(Integer.parseInt(
                properties.getProperty("maxPersonalRequiredEnvironmentVariables", "12")
            ));
    }

    @Test
    void scheduledJobsHaveAuditableTriggerAndLifecycleMetadata() throws IOException {
        Properties properties = loadProperties();
        Set<String> scheduledJobs = scheduledJobKeys();
        Map<String, List<String>> catalog = delimitedCatalog(
            REPOSITORY_ROOT.resolve(properties.getProperty(
                "backgroundJobCatalog",
                "repoguard-backend/src/test/resources/background-job-catalog.txt"
            )),
            6
        );

        assertThat(scheduledJobs.size())
            .as("scheduled job budget")
            .isLessThanOrEqualTo(Integer.parseInt(properties.getProperty("maxScheduledJobCount", "13")));
        assertThat(catalog.keySet())
            .as("every scheduled method must have trigger, timeout, idempotency, resource and shutdown metadata")
            .containsExactlyInAnyOrderElementsOf(scheduledJobs);
        catalog.values().forEach(fields -> assertThat(fields.subList(1, fields.size()))
            .as("scheduled job metadata fields")
            .allMatch(value -> !value.isBlank()));
    }

    @Test
    void productionInterfacesAndDtosDoNotAccumulateUnreferencedDuplicates() throws IOException {
        Properties properties = loadProperties();
        List<String> unreferencedInterfaces = unreferencedInterfaceNames();
        List<String> duplicateDtoNames = duplicateDtoNames();

        assertThat(unreferencedInterfaces)
            .as("production interfaces without a caller")
            .hasSizeLessThanOrEqualTo(Integer.parseInt(properties.getProperty("maxUnreferencedInterfaceCount", "0")));
        assertThat(duplicateDtoNames)
            .as("duplicate production DTO simple names")
            .hasSizeLessThanOrEqualTo(Integer.parseInt(properties.getProperty("maxDuplicateDtoNameCount", "0")));
    }

    @Test
    void productionScriptsHaveAnOwnerTriggerAndRetirementCondition() throws IOException {
        Properties properties = loadProperties();
        Path catalogPath = REPOSITORY_ROOT.resolve(properties.getProperty(
            "productionScriptCatalog",
            "scripts/production-script-catalog.txt"
        ));
        Map<String, List<String>> catalog = delimitedCatalog(catalogPath, 4);
        Set<String> scripts = productionScriptNames();
        Set<String> orphanScripts = new LinkedHashSet<>(scripts);
        orphanScripts.removeAll(catalog.keySet());
        Set<String> staleCatalogEntries = new LinkedHashSet<>(catalog.keySet());
        staleCatalogEntries.removeAll(scripts);

        assertThat(orphanScripts)
            .as("production scripts without a catalog owner")
            .hasSizeLessThanOrEqualTo(Integer.parseInt(properties.getProperty("maxOrphanProductionScriptCount", "0")));
        assertThat(staleCatalogEntries)
            .as("production script catalog entries without a file")
            .isEmpty();
        catalog.values().forEach(fields -> assertThat(fields.subList(1, fields.size()))
            .as("production script catalog metadata fields")
            .allMatch(value -> !value.isBlank()));
    }

    private static List<Path> productionSources() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN_SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static Set<String> requiredPersonalEnvironmentVariables() throws IOException {
        String application = Files.readString(
            REPOSITORY_ROOT.resolve("repoguard-backend/src/main/resources/application.yml"),
            StandardCharsets.UTF_8
        );
        Set<String> variables = new LinkedHashSet<>();
        Matcher matcher = REQUIRED_ENVIRONMENT_VARIABLE.matcher(application);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return variables;
    }

    private static Set<String> scheduledJobKeys() throws IOException {
        Set<String> jobs = new LinkedHashSet<>();
        for (Path source : productionSources()) {
            String sourceText = Files.readString(source, StandardCharsets.UTF_8);
            Matcher matcher = SCHEDULED_METHOD.matcher(sourceText);
            while (matcher.find()) {
                jobs.add(MAIN_SOURCE_ROOT.relativize(source).toString().replace('\\', '/') + "#" + matcher.group(1));
            }
        }
        return jobs;
    }

    private static List<String> unreferencedInterfaceNames() throws IOException {
        List<String> sourceTexts = productionSources().stream()
            .map(path -> read(path))
            .toList();
        List<String> names = new java.util.ArrayList<>();
        for (String sourceText : sourceTexts) {
            Matcher declaration = INTERFACE_DECLARATION.matcher(sourceText);
            while (declaration.find()) {
                String name = declaration.group(1);
                Pattern reference = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
                long references = sourceTexts.stream()
                    .mapToLong(text -> reference.matcher(text).results().count())
                    .sum();
                if (references <= 1) {
                    names.add(name);
                }
            }
        }
        return names.stream().distinct().sorted().toList();
    }

    private static List<String> duplicateDtoNames() throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Path source : productionSources()) {
            String fileName = source.getFileName().toString();
            if (!fileName.endsWith("Dto.java")) {
                continue;
            }
            String name = fileName.substring(0, fileName.length() - ".java".length());
            counts.merge(name, 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(entry -> entry.getKey() + " x" + entry.getValue())
            .sorted()
            .toList();
    }

    private static Set<String> productionScriptNames() throws IOException {
        try (Stream<Path> paths = Files.list(REPOSITORY_ROOT.resolve("scripts"))) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> Set.of(".sh", ".ps1", ".bat", ".cmd").contains(extension(path)))
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, List<String>> delimitedCatalog(Path path, int expectedColumns) throws IOException {
        Map<String, List<String>> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }
            List<String> fields = Arrays.stream(trimmed.split("\\|", -1)).map(String::trim).toList();
            if (fields.size() != expectedColumns || fields.getFirst().isBlank()) {
                throw new IOException("Invalid catalog row in " + path + ": " + line);
            }
            if (entries.put(fields.getFirst(), fields) != null) {
                throw new IOException("Duplicate catalog key in " + path + ": " + fields.getFirst());
            }
        }
        return entries;
    }

    private static List<String> trackedFiles(String path) throws IOException {
        Process process = new ProcessBuilder(
            "git",
            "-c",
            "core.quotePath=false",
            "-C",
            REPOSITORY_ROOT.toString(),
            "ls-files",
            path
        )
            .redirectErrorStream(true)
            .start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git ls-files timed out for " + path);
            }
            if (process.exitValue() != 0) {
                throw new IOException("git ls-files failed for " + path + ": " + output);
            }
            return output.lines().filter(line -> !line.isBlank()).toList();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while listing " + path, ex);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + path, ex);
        }
    }

    private static Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = ArchitectureRatchetTest.class.getClassLoader()
            .getResourceAsStream(RATchet_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing " + RATchet_RESOURCE);
            }
            properties.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static SourceSize sourceSize(Path path) {
        try {
            return new SourceSize(
                MAIN_SOURCE_ROOT.relativize(path).toString().replace('\\', '/'),
                Files.readAllLines(path, StandardCharsets.UTF_8).size()
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + path, ex);
        }
    }

    private record SourceSize(String path, int lines) {
    }
}
