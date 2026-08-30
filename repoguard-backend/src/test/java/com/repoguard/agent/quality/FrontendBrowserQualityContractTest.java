package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FrontendBrowserQualityContractTest {

    @Test
    void routeSmokeCoverageAndBrowserTestsHaveDistinctCommands() throws IOException {
        String packageJson = read("repoguard-frontend/package.json");

        assertThat(packageJson)
            .contains(
                "\"test:route-smoke\": \"vitest run src/smoke\"",
                "\"test:coverage\": \"vitest run --coverage\"",
                "\"test:e2e\": \"npm run build && playwright test\"",
                "\"quality\": \"npm run typecheck && npm run lint && npm run test:coverage\"",
                "\"@playwright/test\"",
                "\"@vitest/coverage-v8\""
            )
            .doesNotContain("\"test:e2e\": \"vitest");
    }

    @Test
    void coverageGateTargetsOwnedApplicationLayers() throws IOException {
        String config = read("repoguard-frontend/vitest.config.ts");

        assertThat(config).contains(
            "provider: \"v8\"",
            "src/api/**/*.ts",
            "src/stores/**/*.ts",
            "src/router/**/*.ts",
            "src/composables/**/*.ts",
            "src/features/**/composables/**/*.ts",
            "statements: 64",
            "branches: 52",
            "functions: 55",
            "lines: 64"
        );
    }

    @Test
    void pullRequestGateRunsChromiumAndRetainsFailureEvidence() throws IOException {
        String workflow = read(".github/workflows/pr-quality.yml");
        String playwright = read("repoguard-frontend/playwright.config.ts");

        assertThat(workflow).contains(
            "npx playwright install --with-deps chromium",
            "run: npm run test:e2e",
            "if: failure()",
            "repoguard-frontend/playwright-report",
            "repoguard-frontend/test-results",
            "retention-days: 7"
        );
        assertThat(playwright).contains(
            "name: \"chromium\"",
            "trace: \"on-first-retry\"",
            "screenshot: \"only-on-failure\"",
            "video: \"retain-on-failure\"",
            "npx vite preview"
        );
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(repositoryRoot().resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (Files.exists(current.resolve("repoguard-frontend/package.json"))) {
                return current;
            }
            current = current.getParent();
        }
        return fail("Cannot locate repository root from " + Path.of("").toAbsolutePath());
    }
}
