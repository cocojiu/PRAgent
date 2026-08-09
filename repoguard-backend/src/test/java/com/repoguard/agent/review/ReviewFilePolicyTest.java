package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.ReviewContextProperties;
import org.junit.jupiter.api.Test;

class ReviewFilePolicyTest {

    private final ReviewFilePolicy policy = ReviewFilePolicy.defaults();

    @Test
    void distinguishesProductionExamplePackageFromRepositoryExamplesAndTests() {
        assertThat(policy.nonProduction("src/main/java/com/example/OrderService.java")).isFalse();
        assertThat(policy.nonProduction("src/test/java/com/example/OrderServiceTest.java")).isTrue();
        assertThat(policy.nonProduction("docs/examples/schema.sql")).isTrue();
        assertThat(policy.nonProduction("repoguard-backend/src/main/resources/db/demo/demo.sql")).isTrue();
    }

    @Test
    void keepsBoundaryAllowListsConfigurableAndExact() {
        ReviewContextProperties properties = new ReviewContextProperties();
        properties.setApprovedMessagePublisherPatterns(java.util.List.of("**/approved/**"));
        properties.setApprovedGithubPublisherPatterns(java.util.List.of("**/publication/Gateway.java"));
        ReviewFilePolicy configured = new ReviewFilePolicy(properties);

        assertThat(configured.approvedMessagePublisher("src/main/java/approved/Publisher.java")).isTrue();
        assertThat(configured.approvedMessagePublisher("src/main/java/service/Publisher.java")).isFalse();
        assertThat(configured.approvedGithubPublisher("src/main/java/publication/Gateway.java")).isTrue();
        assertThat(configured.approvedGithubPublisher("src/main/java/review/Gateway.java")).isFalse();
    }

    @Test
    void fetchesFullContextForEligibleSourceAndConfigFiles() {
        PullRequestChangedFile controller = file(
            "src/main/java/com/example/AdminController.java",
            "+@DeleteMapping(\"/users/{id}\")"
        );
        PullRequestChangedFile plain = file(
            "src/main/java/com/example/Value.java",
            "+String value = \"public\";"
        );
        PullRequestChangedFile test = file(
            "src/test/java/com/example/ValueTest.java",
            "+assertThat(value).isEqualTo(\"public\");"
        );
        PullRequestChangedFile demo = file(
            "docs/examples/Value.java",
            "+class Value {}"
        );

        assertThat(policy.requiresFullFileContext(controller)).isTrue();
        assertThat(policy.requiresFullFileContext(plain)).isTrue();
        assertThat(policy.requiresFullFileContext(test)).isTrue();
        assertThat(policy.requiresFullFileContext(demo)).isFalse();
    }

    @Test
    void excludesCredentialBearingFilesFromFullContext() {
        assertThat(policy.excluded(".env.production")).isTrue();
        assertThat(policy.excluded("deploy/private-key.pem")).isTrue();
        assertThat(policy.excluded("config/service-account.json")).isTrue();
        assertThat(policy.requiresFullFileContext(file("config/credentials.json", "+{}"))).isFalse();
    }

    private PullRequestChangedFile file(String path, String line) {
        return new PullRequestChangedFile(path, "modified", 1, 0, "@@ -1,0 +1,1 @@\n" + line);
    }
}
