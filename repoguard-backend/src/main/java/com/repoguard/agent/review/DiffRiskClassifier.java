package com.repoguard.agent.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class DiffRiskClassifier {

    public int priority(PullRequestChangedFile file) {
        List<String> reasons = reasons(file);
        if (reasons.contains("database_migration")) {
            return 0;
        }
        if (reasons.contains("security_sensitive")) {
            return 1;
        }
        if (reasons.contains("runtime_config")) {
            return 2;
        }
        if (reasons.contains("delivery_pipeline")) {
            return 3;
        }
        return 4;
    }

    public List<String> reasons(PullRequestChangedFile file) {
        String path = file.filename() == null ? "" : file.filename().toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();
        if (path.contains("db/migration") || path.endsWith(".sql")) {
            reasons.add("database_migration");
        }
        if (path.contains("security") || path.contains("auth") || path.contains("token") || path.contains("permission")) {
            reasons.add("security_sensitive");
        }
        if (path.endsWith("application.yml") || path.endsWith("application-prod.yml") || path.contains("config")) {
            reasons.add("runtime_config");
        }
        if (path.contains(".github/") || path.contains("docker") || path.endsWith("pom.xml") || path.endsWith("package.json")) {
            reasons.add("delivery_pipeline");
        }
        return reasons;
    }
}
