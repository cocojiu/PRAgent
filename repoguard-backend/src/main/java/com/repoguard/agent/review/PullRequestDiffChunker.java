package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.entity.ReviewPolicyConfig;
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
        return chunk(diff, ChunkingPolicy.defaults());
    }

    public List<PullRequestDiffChunk> chunk(GithubPullRequestDiff diff, ReviewPolicyConfig config) {
        return chunk(diff, ChunkingPolicy.from(config));
    }

    private List<PullRequestDiffChunk> chunk(GithubPullRequestDiff diff, ChunkingPolicy policy) {
        List<GithubChangedFile> files = diff.files() == null ? List.of() : diff.files();
        if (!requiresChunking(files, policy)) {
            return List.of(toChunk(diff, files, 1, 1));
        }

        List<List<GithubChangedFile>> groupedFiles = new ArrayList<>();
        List<GithubChangedFile> current = new ArrayList<>();
        int currentLines = 0;
        for (GithubChangedFile file : prioritized(files)) {
            int fileLines = changedLines(file);
            boolean currentFull = !current.isEmpty()
                && (current.size() >= policy.maxFilesPerChunk() || currentLines + fileLines > policy.maxLinesPerChunk());
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
            chunks.add(toChunk(diff, groupedFiles.get(i), i + 1, groupedFiles.size(), policy));
        }
        return chunks;
    }

    private boolean requiresChunking(List<GithubChangedFile> files, ChunkingPolicy policy) {
        int totalLines = files.stream().mapToInt(this::changedLines).sum();
        return files.size() > policy.largePrFileThreshold()
            || totalLines > policy.largePrLineThreshold()
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
        return toChunk(source, files, index, total, ChunkingPolicy.defaults());
    }

    private PullRequestDiffChunk toChunk(
        GithubPullRequestDiff source,
        List<GithubChangedFile> files,
        int index,
        int total,
        ChunkingPolicy policy
    ) {
        int additions = files.stream().mapToInt(file -> safeInt(file.additions())).sum();
        int deletions = files.stream().mapToInt(file -> safeInt(file.deletions())).sum();
        return new PullRequestDiffChunk(
            index,
            total,
            new GithubPullRequestDiff(source.owner(), source.repository(), source.prNumber(), files),
            files.size(),
            additions,
            deletions,
            chunkReasons(files, policy)
        );
    }

    private List<String> chunkReasons(List<GithubChangedFile> files, ChunkingPolicy policy) {
        List<String> reasons = new ArrayList<>();
        int changedLines = files.stream().mapToInt(this::changedLines).sum();
        if (files.size() > 1) {
            reasons.add("multi_file");
        }
        if (changedLines > policy.maxLinesPerChunk() / 2) {
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

    private record ChunkingPolicy(
        int maxFilesPerChunk,
        int maxLinesPerChunk,
        int largePrFileThreshold,
        int largePrLineThreshold
    ) {
        static ChunkingPolicy defaults() {
            return new ChunkingPolicy(
                MAX_FILES_PER_CHUNK,
                MAX_LINES_PER_CHUNK,
                LARGE_PR_FILE_THRESHOLD,
                LARGE_PR_LINE_THRESHOLD
            );
        }

        static ChunkingPolicy from(ReviewPolicyConfig config) {
            if (config == null) {
                return defaults();
            }
            return new ChunkingPolicy(
                positive(config.getChunkMaxFiles(), MAX_FILES_PER_CHUNK),
                positive(config.getChunkMaxLines(), MAX_LINES_PER_CHUNK),
                positive(config.getChunkFileThreshold(), LARGE_PR_FILE_THRESHOLD),
                positive(config.getChunkLineThreshold(), LARGE_PR_LINE_THRESHOLD)
            );
        }

        private static int positive(Integer value, int fallback) {
            return value == null || value < 1 ? fallback : value;
        }
    }
}
