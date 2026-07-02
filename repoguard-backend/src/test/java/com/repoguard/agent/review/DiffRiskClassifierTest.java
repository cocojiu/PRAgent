package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.github.GithubChangedFile;
import org.junit.jupiter.api.Test;

class DiffRiskClassifierTest {

    private final DiffRiskClassifier classifier = new DiffRiskClassifier();

    @Test
    void classifiesDatabaseMigrationsAsHighestPriority() {
        GithubChangedFile file = file("repoguard-backend/src/main/resources/db/migration/V37__add_index.sql");

        assertThat(classifier.reasons(file)).containsExactly("database_migration");
        assertThat(classifier.priority(file)).isZero();
    }

    @Test
    void classifiesSecurityAndRuntimeConfigPaths() {
        assertThat(classifier.reasons(file("src/main/java/com/example/security/AuthTokenService.java")))
            .contains("security_sensitive");
        assertThat(classifier.reasons(file("src/main/resources/application-prod.yml")))
            .contains("runtime_config");
    }

    @Test
    void classifiesDeliveryPipelinePaths() {
        assertThat(classifier.reasons(file(".github/workflows/release-images.yml")))
            .containsExactly("delivery_pipeline");
        assertThat(classifier.priority(file("package.json"))).isEqualTo(3);
    }

    private GithubChangedFile file(String filename) {
        return new GithubChangedFile(filename, "MODIFY", 1, 1, null);
    }
}
