package com.repoguard.agent.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Small, explicit ratchets for the continuously growing monolith. Detailed
 * domain dependency rules remain in {@link ApplicationArchitectureTest}; this
 * test makes class-size and forbidden side-channel drift visible in every run.
 */
class ArchitectureRatchetTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java").toAbsolutePath().normalize();
    private static final String RATchet_RESOURCE = "architecture-ratchet.properties";

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
