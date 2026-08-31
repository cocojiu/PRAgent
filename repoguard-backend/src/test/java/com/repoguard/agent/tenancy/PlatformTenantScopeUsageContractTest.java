package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformTenantScopeUsageContractTest {

    @Test
    void onlyTenantScheduledTaskRunnerCanOpenPlatformScope() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        List<Path> callers;
        try (var paths = Files.walk(sourceRoot)) {
            callers = paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(this::opensPlatformScope)
                .map(sourceRoot::relativize)
                .toList();
        }

        assertThat(callers).containsExactly(Path.of(
            "com/repoguard/agent/tenancy/TenantScheduledTaskRunner.java"
        ));
    }

    private boolean opensPlatformScope(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8)
                .contains("PlatformTenantScope.open(");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect " + path, exception);
        }
    }
}
