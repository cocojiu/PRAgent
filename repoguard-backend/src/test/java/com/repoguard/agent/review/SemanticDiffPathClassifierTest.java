package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.github.GithubChangedFile;
import org.junit.jupiter.api.Test;

class SemanticDiffPathClassifierTest {

    private final SemanticDiffPathClassifier classifier = new SemanticDiffPathClassifier();

    @Test
    void normalizesChangedFilePathForStableClassification() {
        GithubChangedFile file = new GithubChangedFile(
            "SRC\\Main\\Java\\Com\\Example\\OrderService.java",
            "modified",
            1,
            0,
            "@@ patch"
        );

        assertThat(classifier.normalizedPath(file))
            .isEqualTo("src/main/java/com/example/orderservice.java");
    }

    @Test
    void resolvesSemanticDomainFromPathCategory() {
        assertThat(classifier.semanticDomain("src/main/resources/db/migration/v1__init.sql")).isEqualTo("database");
        assertThat(classifier.semanticDomain("src/main/resources/application-prod.yml")).isEqualTo("config");
        assertThat(classifier.semanticDomain(".github/workflows/release.yml")).isEqualTo("config");
        assertThat(classifier.semanticDomain("src/test/java/com/example/order/orderservicetest.java")).isEqualTo("test");
        assertThat(classifier.semanticDomain("docs/release/notes.md")).isEqualTo("docs");
        assertThat(classifier.semanticDomain("src/main/java/com/example/order/orderservice.java")).isEqualTo("source");
    }

    @Test
    void resolvesModuleKeyBySourceLayout() {
        assertThat(classifier.moduleKey("src/main/java/com/example/order/orderservice.java"))
            .isEqualTo("src/main/java/com/example/order");
        assertThat(classifier.moduleKey("src/test/java/com/example/order/orderservicetest.java"))
            .isEqualTo("src/test/java/com/example/order");
        assertThat(classifier.moduleKey("src/main/resources/db/migration/v1__init.sql"))
            .isEqualTo("src/main/resources/db/migration");
        assertThat(classifier.moduleKey(".github/workflows/release.yml"))
            .isEqualTo(".github/workflows");
    }

    @Test
    void resolvesSemanticReasonFromPathCategory() {
        assertThat(classifier.semanticReason("src/test/java/com/example/order/orderservicetest.java"))
            .isEqualTo("test_scope");
        assertThat(classifier.semanticReason("src/main/java/com/example/order/orderservice.java"))
            .isEqualTo("code_scope");
        assertThat(classifier.semanticReason("src/main/resources/db/migration/v1__init.sql"))
            .isEqualTo("sql_statement");
        assertThat(classifier.semanticReason("src/main/resources/application-prod.yml"))
            .isEqualTo("config_section");
        assertThat(classifier.semanticReason("docs/release/notes.md"))
            .isEqualTo("documentation_section");
        assertThat(classifier.semanticReason("scripts/deploy.sh"))
            .isEqualTo("path_scope");
    }
}
