package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LogbackTestProfileContractTest {

    @Test
    void testProfileKeepsFailureEvidenceAndFiltersOnlyExpectedFallbackNoise() throws Exception {
        String configuration = Files.readString(Path.of("src/test/resources/logback-test.xml"));

        assertThat(configuration)
            .contains("<root level=\"WARN\">")
            .contains("<filter class=\"com.repoguard.agent.testlogging.SuppressExpectedLlmFallbackFilter\"/>")
            .contains("%safeEx")
            .doesNotContain("<root level=\"ERROR\">");
    }
}
