package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class PullRequestDiffChunker {

    private static final int MAX_FILES_PER_CHUNK = 4;
    private static final int MAX_LINES_PER_CHUNK = 450;
    private static final int LARGE_PR_FILE_THRESHOLD = 6;
    private static final int LARGE_PR_LINE_THRESHOLD = 700;

    public List<PullRequestDiffChunk> chunk(GithubPullRequestDiff diff) {
        List<GithubChangedFile> files = diff.files() == null ? List.of() : diff.files();
        if (!requiresChunking(files)) {
            return List.of(toChunk(diff, files, 1, 1));
        }

        List<List<GithubChangedFile>> groupedFiles = new ArrayList<>();
        List<GithubChangedFile> current = new ArrayList<>();
        int currentLines = 0;
        for (GithubChangedFile file : prioritized(files)) {
            int fileLines = changedLines(file);
            boolean currentFull = !current.isEmpty()
                && (current.size() >= MAX_FILES_PER_CHUNK || currentLines + fileLines > MAX_LINES_PER_CHUNK);
            if (currentFull) {
                groupedFiles.add(current);
                current = new ArrayList<>();
                currentLines = 0;
            }
            current.add(file);
            currentLines += fileLines;
        }
        if (!current.isEmpty()) {
            groupedFiles.add(current);
        }

        List<PullRequestDiffChunk> chunks = new ArrayList<>();
        for (int i = 0; i < groupedFiles.size(); i++) {
            chunks.add(toChunk(diff, groupedFiles.get(i), i + 1, groupedFiles.size()));
        }
        return chunks;
    }

    private boolean requiresChunking(List<GithubChangedFile> files) {
        int totalLines = files.stream().mapToInt(this::changedLines).sum();
        return files.size() > LARGE_PR_FILE_THRESHOLD
            || totalLines > LARGE_PR_LINE_THRESHOLD
            || (files.stream().anyMatch(file -> !riskReasons(file).isEmpty()) && files.size() > 1);
    }

    private List<GithubChangedFile> prioritized(List<GithubChangedFile> files) {
        return files.stream()
            .sorted(Comparator
                .comparingInt(this::riskPriority)
                .thenComparing(GithubChangedFile::filename, Comparator.nullsLast(String::compareTo)))
            .toList();
    }

    private int riskPriority(GithubChangedFile file) {
        List<String> reasons = riskReasons(file);
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

    private PullRequestDiffChunk toChunk(GithubPullRequestDiff source, List<GithubChangedFile> files, int index, int total) {
        int additions = files.stream().mapToInt(file -> safeInt(file.additions())).sum();
        int deletions = files.stream().mapToInt(file -> safeInt(file.deletions())).sum();
        return new PullRequestDiffChunk(
            index,
            total,
            new GithubPullRequestDiff(source.owner(), source.repository(), source.prNumber(), files),
            files.size(),
            additions,
            deletions,
            chunkReasons(files)
        );
    }

    private List<String> chunkReasons(List<GithubChangedFile> files) {
        List<String> reasons = new ArrayList<>();
        int changedLines = files.stream().mapToInt(this::changedLines).sum();
        if (files.size() > 1) {
            reasons.add("multi_file");
        }
        if (changedLines > MAX_LINES_PER_CHUNK / 2) {
            reasons.add("large_churn");
        }
        files.stream()
            .flatMap(file -> riskReasons(file).stream())
            .distinct()
            .forEach(reasons::add);
        return reasons.isEmpty() ? List.of("standard") : reasons;
    }

    private List<String> riskReasons(GithubChangedFile file) {
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

    private int changedLines(GithubChangedFile file) {
        return safeInt(file.additions()) + safeInt(file.deletions());
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
