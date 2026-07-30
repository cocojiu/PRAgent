package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RiskFilePrioritizerTest {

    private final RiskFilePrioritizer prioritizer = new RiskFilePrioritizer(new DiffRiskClassifier());

    @Test
    void prioritizeFilesOrdersByRiskThenPath() {
        List<PullRequestChangedFile> files = List.of(
            file("src/main/java/com/example/UserService.java"),
            file("package.json"),
            file("src/main/resources/application-prod.yml"),
            file("src/main/java/com/example/security/AuthTokenFilter.java"),
            file(".github/workflows/release.yml"),
            file("src/main/resources/db/migration/V39__session_csrf.sql")
        );

        List<PullRequestChangedFile> prioritized = prioritizer.prioritizeFiles(files);

        assertThat(prioritized)
            .extracting(PullRequestChangedFile::filename)
            .containsExactly(
                "src/main/resources/db/migration/V39__session_csrf.sql",
                "src/main/java/com/example/security/AuthTokenFilter.java",
                "src/main/resources/application-prod.yml",
                ".github/workflows/release.yml",
                "package.json",
                "src/main/java/com/example/UserService.java"
            );
    }

    @Test
    void prioritizeSegmentsOrdersByRiskSemanticGroupAndPath() {
        List<SemanticDiffSegment> segments = List.of(
            segment("src/main/java/com/example/payment/PaymentService.java", 4, "payment", "refund"),
            segment("src/main/java/com/example/order/OrderService.java", 1, "order", "cancel"),
            segment("src/main/java/com/example/order/OrderController.java", 1, "order", "approve"),
            segment("src/main/resources/application-prod.yml", 2, "config", "datasource"),
            segment("src/main/resources/db/migration/V39__session_csrf.sql", 0, "database", "migration")
        );

        List<SemanticDiffSegment> prioritized = prioritizer.prioritizeSegments(segments);

        assertThat(prioritized)
            .extracting(segment -> segment.file().filename() + "#" + segment.semanticKey())
            .containsExactly(
                "src/main/resources/db/migration/V39__session_csrf.sql#migration",
                "src/main/java/com/example/order/OrderController.java#approve",
                "src/main/java/com/example/order/OrderService.java#cancel",
                "src/main/resources/application-prod.yml#datasource",
                "src/main/java/com/example/payment/PaymentService.java#refund"
            );
    }

    private PullRequestChangedFile file(String path) {
        return new PullRequestChangedFile(path, "modified", 10, 2, "@@ patch");
    }

    private SemanticDiffSegment segment(String path, int priority, String groupKey, String semanticKey) {
        return new SemanticDiffSegment(
            file(path),
            groupKey,
            semanticKey,
            "code_scope",
            priority,
            10,
            2
        );
    }
}
