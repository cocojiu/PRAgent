package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SemanticDiffScopeExtractorTest {

    private final SemanticDiffScopeExtractor extractor = new SemanticDiffScopeExtractor();
    private final SemanticDiffPathClassifier pathClassifier = new SemanticDiffPathClassifier();

    @Test
    void extractsJavaTypeBeforeMethodContext() {
        String scope = extractor.scope(
            "src/main/java/com/example/order/orderservice.java",
            """
                @@ -1,5 +1,8 @@ public class OrderService {
                +public class OrderService {
                +    public void approveOrder(Order order) {
                +    }
                """,
            pathClassifier
        );

        assertThat(scope).isEqualTo("orderservice");
    }

    @Test
    void extractsJavaMethodWhenTypeIsAbsent() {
        String scope = extractor.scope(
            "src/main/java/com/example/order/orderservice.java",
            """
                @@ -30,6 +30,8 @@ public void cancelOrder(Order order) {
                +public void cancelOrder(Order order) {
                +    order.cancel();
                +}
                """,
            pathClassifier
        );

        assertThat(scope).isEqualTo("cancelorder");
    }

    @Test
    void extractsSqlStatementVerb() {
        String scope = extractor.scope(
            "src/main/resources/db/migration/v99__drop_legacy.sql",
            """
                @@ -1,1 +1,2 @@ migration
                +DROP TABLE legacy_token;
                """,
            pathClassifier
        );

        assertThat(scope).isEqualTo("drop");
    }

    @Test
    void extractsConfigKeyBeforeHunkFallback() {
        String scope = extractor.scope(
            "src/main/resources/application-prod.yml",
            """
                @@ -1,4 +1,6 @@ datasource:
                 datasource:
                +  maximum-pool-size: 20
                """,
            pathClassifier
        );

        assertThat(scope).isEqualTo("datasource");
    }

    @Test
    void fallsBackToSanitizedHunkContextForPlainFiles() {
        String scope = extractor.scope(
            "scripts/release.sh",
            "@@ -3,4 +3,6 @@ deploy production cluster",
            pathClassifier
        );

        assertThat(scope).isEqualTo("deploy_production_cluster");
    }
}
