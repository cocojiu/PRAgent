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
    private static final Pattern COMPONENT_ANNOTATION = Pattern.compile(
        "(?m)^\\s*@(Component|Service|Repository)(?:\\([^\\r\\n]*\\))?"
    );
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
        "\\b(?:public\\s+)?(?:abstract\\s+)?(?:final\\s+)?class\\s+"
            + "([A-Za-z_$][A-Za-z0-9_$]*)"
    );
    private static final Pattern IMPLEMENTED_INTERFACE = Pattern.compile(
        "\\b(?:implements|extends)\\s+"
            + "([A-Za-z_$][A-Za-z0-9_$]*(?:\\s*,\\s*[A-Za-z_$][A-Za-z0-9_$]*)*)"
    );
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
        "\\b(?:class|interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
    );
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
        "(?m)^\\s*import\\s+(?:static\\s+)?[^;]+;\\s*$"
    );
    private static final Pattern FRAMEWORK_ENTRY_POINT = Pattern.compile(
        "@(Scheduled|RabbitListener|EventListener|Intercepts)\\b"
            + "|\\bextends\\s+OncePerRequestFilter\\b"
            + "|\\bimplements\\s+(?:FlywayConfigurationCustomizer|InitializingBean)\\b"
    );
    private static final Pattern LEGACY_CONFIGURATION = Pattern.compile(
        "\\\"(app\\.runtime\\.(?:api|worker)\\.enabled|REPOGUARD_(?:API|WORKER)_ENABLED)\\\""
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
            .isLessThanOrEqualTo(Long.parseLong(properties.getProperty("maxProductionJavaLines", "70074")));
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
        List<SourceText> sources = productionSourceTexts();
        List<String> unreferencedBeans = unreferencedBeanNames(sources);
        List<String> unreferencedInterfaces = unreferencedInterfaceNames(sources);
        List<String> duplicateDtoNames = duplicateDtoNames();

        assertThat(unreferencedBeans)
            .as("production beans without a direct or interface-dispatch caller")
            .hasSizeLessThanOrEqualTo(Integer.parseInt(properties.getProperty("maxUnreferencedBeanCount", "0")));
        assertThat(unreferencedInterfaces)
            .as("production interfaces without a caller")
            .hasSizeLessThanOrEqualTo(Integer.parseInt(properties.getProperty("maxUnreferencedInterfaceCount", "0")));
        assertThat(duplicateDtoNames)
            .as("duplicate production DTO simple names")
            .hasSizeLessThanOrEqualTo(Integer.parseInt(properties.getProperty("maxDuplicateDtoNameCount", "0")));
    }

    @Test
    void beanAndInterfaceAuditRequiresARealCallerInsteadOfCountingDeclarations() {
        List<SourceText> sources = List.of(
            source("Orphan.java", """
                @Component
                class Orphan {
                    Orphan() {
                    }
                }
                """),
            source("OrphanPort.java", "interface OrphanPort {}"),
            source("OrphanPortAdapter.java", """
                @Component
                class OrphanPortAdapter implements OrphanPort {
                    OrphanPortAdapter() {
                    }
                }
                """),
            source("Direct.java", """
                @Component
                class Direct {
                    Direct() {
                    }
                }
                """),
            source("DirectCaller.java", """
                import example.Direct;
                class DirectCaller {
                    DirectCaller(Direct direct) {
                    }
                }
                """),
            source("Channel.java", "interface Channel {}"),
            source("BaseChannel.java", "abstract class BaseChannel implements Channel {}"),
            source("ChannelAdapter.java", """
                @Component
                class ChannelAdapter extends BaseChannel {
                    ChannelAdapter() {
                    }
                }
                """),
            source("ChannelRegistry.java", """
                import java.util.List;
                class ChannelRegistry {
                    ChannelRegistry(List<Channel> channels) {
                    }
                }
                """),
            source("ScheduledWorker.java", """
                @Component
                class ScheduledWorker {
                    @Scheduled
                    void run() {
                    }
                }
                """)
        );

        assertThat(unreferencedBeanNames(sources))
            .containsExactly("Orphan", "OrphanPortAdapter");
        assertThat(unreferencedInterfaceNames(sources))
            .containsExactly("OrphanPort");
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

    @Test
    void deprecatedConfigurationHasReplacementVerificationAndRetirementMetadata() throws IOException {
        Properties properties = loadProperties();
        Path catalogPath = REPOSITORY_ROOT.resolve(properties.getProperty(
            "configurationLifecycleCatalog",
            "scripts/configuration-lifecycle-catalog.txt"
        ));
        Map<String, List<String>> catalog = delimitedCatalog(catalogPath, 5);
        Set<String> deprecatedConfigurations = deprecatedConfigurationNames();

        assertThat(deprecatedConfigurations.size())
            .as("deprecated configuration budget")
            .isLessThanOrEqualTo(Integer.parseInt(
                properties.getProperty("maxDeprecatedConfigurationCount", "4")
            ));
        assertThat(catalog.keySet())
            .as("every deprecated configuration must have a replacement and retirement plan")
            .containsExactlyInAnyOrderElementsOf(deprecatedConfigurations);
        catalog.values().forEach(fields -> {
            assertThat(fields.get(1)).isEqualTo("compatibility-only");
            assertThat(fields.subList(2, fields.size()))
                .as("deprecated configuration lifecycle metadata")
                .allMatch(value -> !value.isBlank());
        });
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

    private static List<String> unreferencedBeanNames(List<SourceText> sources) {
        Set<String> names = new LinkedHashSet<>();
        for (SourceText source : sources) {
            Matcher annotation = COMPONENT_ANNOTATION.matcher(source.text());
            while (annotation.find()) {
                Matcher declaration = CLASS_DECLARATION.matcher(source.text());
                declaration.region(annotation.end(), Math.min(source.text().length(), annotation.end() + 1000));
                if (!declaration.find()) {
                    continue;
                }
                String name = declaration.group(1);
                if (!hasReferenceOutsideDeclaration(name, source, sources)
                    && !referencedThroughInterface(source, sources, new LinkedHashSet<>())
                    && !FRAMEWORK_ENTRY_POINT.matcher(source.text()).find()) {
                    names.add(name);
                }
            }
        }
        return names.stream().sorted().toList();
    }

    private static boolean referencedThroughInterface(
        SourceText source,
        List<SourceText> sources,
        Set<String> visitedTypes
    ) {
        Matcher implemented = IMPLEMENTED_INTERFACE.matcher(source.text());
        while (implemented.find()) {
            for (String name : implemented.group(1).split(",")) {
                String typeName = name.trim();
                if (!visitedTypes.add(typeName)) {
                    continue;
                }
                SourceText declaration = declaringSource(typeName, sources);
                if (declaration == null) {
                    continue;
                }
                if (hasDispatchedReference(typeName, declaration, sources)
                    || referencedThroughInterface(declaration, sources, visitedTypes)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> deprecatedConfigurationNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (Path source : productionSources()) {
            Matcher matcher = LEGACY_CONFIGURATION.matcher(read(source));
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
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

    private static List<String> unreferencedInterfaceNames(List<SourceText> sources) {
        List<String> names = new java.util.ArrayList<>();
        for (SourceText source : sources) {
            Matcher declaration = INTERFACE_DECLARATION.matcher(source.text());
            while (declaration.find()) {
                String name = declaration.group(1);
                if (!hasSemanticReference(name, source, sources)) {
                    names.add(name);
                }
            }
        }
        return names.stream().distinct().sorted().toList();
    }

    private static List<SourceText> productionSourceTexts() throws IOException {
        return productionSources().stream()
            .map(path -> source(path.toString(), read(path)))
            .toList();
    }

    private static boolean hasReferenceOutsideDeclaration(
        String typeName,
        SourceText declaration,
        List<SourceText> sources
    ) {
        Pattern reference = Pattern.compile("\\b" + Pattern.quote(typeName) + "\\b");
        return sources.stream()
            .filter(source -> !source.path().equals(declaration.path()))
            .map(SourceText::semanticText)
            .anyMatch(text -> reference.matcher(text).find());
    }

    private static boolean hasDispatchedReference(
        String typeName,
        SourceText declaration,
        List<SourceText> sources
    ) {
        return declaresInterface(typeName, declaration)
            ? hasSemanticReference(typeName, declaration, sources)
            : hasReferenceOutsideDeclaration(typeName, declaration, sources);
    }

    private static boolean hasSemanticReference(
        String typeName,
        SourceText declaration,
        List<SourceText> sources
    ) {
        Pattern reference = Pattern.compile("\\b" + Pattern.quote(typeName) + "\\b");
        Pattern ownDeclaration = Pattern.compile(
            "\\b(?:class|interface)\\s+" + Pattern.quote(typeName) + "\\b"
        );
        return sources.stream()
            .map(source -> {
                String text = source.semanticText();
                return source.path().equals(declaration.path())
                    ? ownDeclaration.matcher(text).replaceAll("")
                    : text;
            })
            .anyMatch(text -> reference.matcher(text).find());
    }

    private static boolean declaresInterface(String typeName, SourceText source) {
        return Pattern.compile("\\binterface\\s+" + Pattern.quote(typeName) + "\\b")
            .matcher(source.text())
            .find();
    }

    private static SourceText declaringSource(String typeName, List<SourceText> sources) {
        for (SourceText source : sources) {
            Matcher declaration = TYPE_DECLARATION.matcher(source.text());
            while (declaration.find()) {
                if (typeName.equals(declaration.group(1))) {
                    return source;
                }
            }
        }
        return null;
    }

    private static SourceText source(String path, String text) {
        String semanticText = IMPORT_DECLARATION.matcher(text).replaceAll("");
        semanticText = IMPLEMENTED_INTERFACE.matcher(semanticText).replaceAll("");
        return new SourceText(path, text, semanticText);
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
                .filter(path -> Set.of(".sh", ".ps1", ".bat", ".cmd", ".py").contains(extension(path)))
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

    private record SourceText(String path, String text, String semanticText) {
    }
}
