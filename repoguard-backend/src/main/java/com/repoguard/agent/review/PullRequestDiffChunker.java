package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PullRequestDiffChunker {

    private static final int MAX_FILES_PER_CHUNK = 4;
    private static final int MAX_LINES_PER_CHUNK = 450;
    private static final int LARGE_PR_FILE_THRESHOLD = 6;
    private static final int LARGE_PR_LINE_THRESHOLD = 700;
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@\\s+[^@]*@@\\s*(.*)$");
    private static final Pattern JAVA_TYPE = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern JAVA_METHOD = Pattern.compile(
        "\\b(?:public|protected|private|static|final|synchronized|abstract|native|default|\\s)+"
            + "[\\w<>\\[\\], ?]+\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\("
    );
    private static final Pattern YAML_KEY = Pattern.compile("^[+-]?\\s*([A-Za-z0-9_.-]+)\\s*:");

    private final DiffRiskClassifier riskClassifier;

    public PullRequestDiffChunker() {
        this(new DiffRiskClassifier());
    }

    PullRequestDiffChunker(DiffRiskClassifier riskClassifier) {
        this.riskClassifier = riskClassifier;
    }

    public List<PullRequestDiffChunk> chunk(GithubPullRequestDiff diff) {
        return chunk(diff, ChunkingPolicy.defaults());
    }

    public List<PullRequestDiffChunk> chunk(GithubPullRequestDiff diff, ReviewPolicyConfig config) {
        return chunk(diff, ChunkingPolicy.from(config));
    }

    public List<PullRequestDiffChunk> chunk(GithubPullRequestDiff diff, ReviewPolicySettings settings) {
        return chunk(diff, ChunkingPolicy.from(settings));
    }

    private List<PullRequestDiffChunk> chunk(GithubPullRequestDiff diff, ChunkingPolicy policy) {
        List<GithubChangedFile> files = diff.files() == null ? List.of() : diff.files();
        if (!requiresChunking(files, policy)) {
            return List.of(toChunk(diff, files, 1, 1));
        }

        List<List<SemanticDiffSegment>> groupedSegments = new ArrayList<>();
        List<SemanticDiffSegment> current = new ArrayList<>();
        int currentLines = 0;
        String currentGroupKey = null;
        for (SemanticDiffSegment segment : prioritizedSegments(files)) {
            int segmentLines = segment.changedLines();
            boolean semanticBoundary = currentGroupKey != null
                && (!currentGroupKey.equals(segment.chunkGroupKey()) || splitsSameFileScope(current, segment));
            boolean currentFull = !current.isEmpty()
                && (distinctFileCount(current) >= policy.maxFilesPerChunk()
                    || currentLines + segmentLines > policy.maxLinesPerChunk());
            if (currentFull || (semanticBoundary && currentLines > 0)) {
                groupedSegments.add(current);
                current = new ArrayList<>();
                currentLines = 0;
                currentGroupKey = null;
            }
            current.add(segment);
            currentLines += segmentLines;
            currentGroupKey = segment.chunkGroupKey();
        }
        if (!current.isEmpty()) {
            groupedSegments.add(current);
        }

        List<PullRequestDiffChunk> chunks = new ArrayList<>();
        for (int i = 0; i < groupedSegments.size(); i++) {
            chunks.add(toSemanticChunk(diff, groupedSegments.get(i), i + 1, groupedSegments.size(), policy));
        }
        return chunks;
    }

    private boolean requiresChunking(List<GithubChangedFile> files, ChunkingPolicy policy) {
        int totalLines = files.stream().mapToInt(this::changedLines).sum();
        return files.size() > policy.largePrFileThreshold()
            || totalLines > policy.largePrLineThreshold()
            || (files.stream().anyMatch(file -> !riskClassifier.reasons(file).isEmpty()) && files.size() > 1);
    }

    private List<GithubChangedFile> prioritized(List<GithubChangedFile> files) {
        return files.stream()
            .sorted(Comparator
                .comparingInt(riskClassifier::priority)
                .thenComparing(GithubChangedFile::filename, Comparator.nullsLast(String::compareTo)))
            .toList();
    }

    private List<SemanticDiffSegment> prioritizedSegments(List<GithubChangedFile> files) {
        return prioritized(files).stream()
            .flatMap(file -> semanticSegments(file).stream())
            .sorted(Comparator
                .comparingInt(SemanticDiffSegment::riskPriority)
                .thenComparing(SemanticDiffSegment::chunkGroupKey)
                .thenComparing(SemanticDiffSegment::semanticKey)
                .thenComparing(segment -> segment.file().filename(), Comparator.nullsLast(String::compareTo)))
            .toList();
    }

    private PullRequestDiffChunk toChunk(GithubPullRequestDiff source, List<GithubChangedFile> files, int index, int total) {
        return toChunk(source, files, index, total, ChunkingPolicy.defaults());
    }

    private PullRequestDiffChunk toSemanticChunk(
        GithubPullRequestDiff source,
        List<SemanticDiffSegment> segments,
        int index,
        int total,
        ChunkingPolicy policy
    ) {
        List<GithubChangedFile> files = segments.stream().map(SemanticDiffSegment::file).toList();
        int additions = segments.stream().mapToInt(SemanticDiffSegment::additions).sum();
        int deletions = segments.stream().mapToInt(SemanticDiffSegment::deletions).sum();
        return new PullRequestDiffChunk(
            index,
            total,
            new GithubPullRequestDiff(source.owner(), source.repository(), source.prNumber(), files),
            distinctFileCount(segments),
            additions,
            deletions,
            semanticChunkReasons(source.files(), segments, policy)
        );
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
            .flatMap(file -> riskClassifier.reasons(file).stream())
            .distinct()
            .forEach(reasons::add);
        return reasons.isEmpty() ? List.of("standard") : reasons;
    }

    private List<String> semanticChunkReasons(
        List<GithubChangedFile> sourceFiles,
        List<SemanticDiffSegment> segments,
        ChunkingPolicy policy
    ) {
        List<String> reasons = new ArrayList<>();
        int changedLines = segments.stream().mapToInt(SemanticDiffSegment::changedLines).sum();
        if (distinctSourceFileCount(sourceFiles) > 1 || distinctFileCount(segments) > 1) {
            reasons.add("multi_file");
        }
        if (segments.stream().map(SemanticDiffSegment::chunkGroupKey).distinct().count() == 1) {
            reasons.add("semantic_scope");
        }
        if (segments.size() > distinctFileCount(segments)) {
            reasons.add("split_file_scope");
        }
        if (changedLines > policy.maxLinesPerChunk() / 2) {
            reasons.add("large_churn");
        }
        segments.stream()
            .map(SemanticDiffSegment::semanticReason)
            .distinct()
            .forEach(reasons::add);
        segments.stream()
            .flatMap(segment -> riskClassifier.reasons(segment.file()).stream())
            .distinct()
            .forEach(reasons::add);
        return reasons.isEmpty() ? List.of("standard") : reasons;
    }

    private List<SemanticDiffSegment> semanticSegments(GithubChangedFile file) {
        String patch = file.patch();
        if (patch == null || patch.isBlank() || !patch.contains("@@")) {
            return List.of(toSegment(
                file,
                patch,
                chunkGroupKey(file),
                semanticKey(file, patch),
                semanticReason(file, patch),
                safeInt(file.additions()),
                safeInt(file.deletions())
            ));
        }

        List<String> hunks = splitHunks(patch);
        if (hunks.size() <= 1) {
            return List.of(toSegment(
                file,
                patch,
                chunkGroupKey(file),
                semanticKey(file, patch),
                semanticReason(file, patch),
                safeInt(file.additions()),
                safeInt(file.deletions())
            ));
        }

        List<SemanticDiffSegment> segments = new ArrayList<>();
        List<Integer> visibleAdditions = hunks.stream().map(hunk -> countPatchLines(hunk, '+')).toList();
        List<Integer> visibleDeletions = hunks.stream().map(hunk -> countPatchLines(hunk, '-')).toList();
        int totalVisibleAdditions = visibleAdditions.stream().mapToInt(Integer::intValue).sum();
        int totalVisibleDeletions = visibleDeletions.stream().mapToInt(Integer::intValue).sum();
        int allocatedAdditions = 0;
        int allocatedDeletions = 0;
        for (int i = 0; i < hunks.size(); i++) {
            String hunk = hunks.get(i);
            boolean last = i == hunks.size() - 1;
            int additions = allocatedLines(visibleAdditions.get(i), totalVisibleAdditions, safeInt(file.additions()), allocatedAdditions, last);
            int deletions = allocatedLines(visibleDeletions.get(i), totalVisibleDeletions, safeInt(file.deletions()), allocatedDeletions, last);
            allocatedAdditions += additions;
            allocatedDeletions += deletions;
            segments.add(toSegment(
                file,
                hunk,
                chunkGroupKey(file),
                semanticKey(file, hunk),
                semanticReason(file, hunk),
                additions,
                deletions
            ));
        }
        return segments;
    }

    private List<String> splitHunks(String patch) {
        List<String> hunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : patch.split("\\R")) {
            if (line.startsWith("@@") && !current.isEmpty()) {
                hunks.add(String.join("\n", current));
                current = new ArrayList<>();
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            hunks.add(String.join("\n", current));
        }
        return hunks;
    }

    private SemanticDiffSegment toSegment(
        GithubChangedFile file,
        String patch,
        String chunkGroupKey,
        String semanticKey,
        String semanticReason,
        int additions,
        int deletions
    ) {
        return new SemanticDiffSegment(
            new GithubChangedFile(file.filename(), file.status(), additions, deletions, patch),
            chunkGroupKey,
            semanticKey,
            semanticReason,
            riskClassifier.priority(file),
            additions,
            deletions
        );
    }

    private int countPatchLines(String patch, char marker) {
        if (patch == null || patch.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : patch.split("\\R")) {
            if (line.length() > 1 && line.charAt(0) == marker && !line.startsWith("+++") && !line.startsWith("---")) {
                count++;
            }
        }
        return count;
    }

    private int allocatedLines(int visibleLines, int totalVisibleLines, int sourceLines, int alreadyAllocated, boolean last) {
        if (sourceLines == 0) {
            return visibleLines;
        }
        if (last || totalVisibleLines == 0) {
            return Math.max(0, sourceLines - alreadyAllocated);
        }
        return Math.max(0, (int) Math.round((double) visibleLines * sourceLines / totalVisibleLines));
    }

    private String semanticKey(GithubChangedFile file, String patch) {
        String path = normalizedPath(file);
        String scope = semanticScope(path, patch);
        return semanticDomain(path) + ":" + moduleKey(path) + ":" + scope;
    }

    private String chunkGroupKey(GithubChangedFile file) {
        String path = normalizedPath(file);
        return semanticDomain(path) + ":" + moduleKey(path);
    }

    private String semanticReason(GithubChangedFile file, String patch) {
        String path = normalizedPath(file);
        if (isTestPath(path)) {
            return "test_scope";
        }
        if (isJavaLike(path)) {
            return "code_scope";
        }
        if (path.endsWith(".sql")) {
            return "sql_statement";
        }
        if (isConfigLike(path)) {
            return "config_section";
        }
        if (path.endsWith(".md")) {
            return "documentation_section";
        }
        return "path_scope";
    }

    private String semanticScope(String path, String patch) {
        if (isJavaLike(path)) {
            return firstMatch(patch, JAVA_TYPE, 2)
                .or(() -> firstMatch(patch, JAVA_METHOD, 1))
                .orElse(hunkContext(patch));
        }
        if (path.endsWith(".sql")) {
            return firstSqlVerb(patch);
        }
        if (isConfigLike(path)) {
            return firstMatch(patch, YAML_KEY, 1).orElse(hunkContext(patch));
        }
        return hunkContext(patch);
    }

    private java.util.Optional<String> firstMatch(String patch, Pattern pattern, int group) {
        if (patch == null) {
            return java.util.Optional.empty();
        }
        Matcher matcher = pattern.matcher(patch);
        if (matcher.find()) {
            return java.util.Optional.of(sanitizeKey(matcher.group(group)));
        }
        return java.util.Optional.empty();
    }

    private String firstSqlVerb(String patch) {
        if (patch == null) {
            return "file";
        }
        for (String line : patch.split("\\R")) {
            String normalized = stripPatchMarker(line).trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("create ")) {
                return "create";
            }
            if (normalized.startsWith("alter ")) {
                return "alter";
            }
            if (normalized.startsWith("drop ")) {
                return "drop";
            }
            if (normalized.startsWith("insert ")) {
                return "insert";
            }
            if (normalized.startsWith("update ")) {
                return "update";
            }
            if (normalized.startsWith("delete ")) {
                return "delete";
            }
        }
        return hunkContext(patch);
    }

    private String hunkContext(String patch) {
        if (patch != null) {
            Matcher matcher = HUNK_HEADER.matcher(patch);
            if (matcher.find() && !matcher.group(1).isBlank()) {
                return sanitizeKey(matcher.group(1));
            }
        }
        return "file";
    }

    private String stripPatchMarker(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        char marker = line.charAt(0);
        if (marker == '+' || marker == '-' || marker == ' ') {
            return line.substring(1);
        }
        return line;
    }

    private String semanticDomain(String path) {
        if (path.contains("db/migration") || path.endsWith(".sql")) {
            return "database";
        }
        if (isConfigLike(path)) {
            return "config";
        }
        if (path.contains(".github/") || path.contains("docker") || path.endsWith("pom.xml") || path.endsWith("package.json")) {
            return "delivery";
        }
        if (isTestPath(path)) {
            return "test";
        }
        if (path.endsWith(".md")) {
            return "docs";
        }
        return "source";
    }

    private String moduleKey(String path) {
        if (path.contains("/src/main/java/")) {
            return after(path, "/src/main/java/", 3);
        }
        if (path.contains("/src/test/java/")) {
            return after(path, "/src/test/java/", 3);
        }
        if (path.contains("/src/main/resources/")) {
            return after(path, "/src/main/resources/", 2);
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    private String after(String path, String marker, int depth) {
        String suffix = path.substring(path.indexOf(marker) + marker.length());
        String[] parts = suffix.split("/");
        List<String> selected = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                selected.add(part);
            }
            if (selected.size() == depth) {
                break;
            }
        }
        return selected.isEmpty() ? "root" : String.join("/", selected);
    }

    private boolean isJavaLike(String path) {
        return path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".groovy");
    }

    private boolean isConfigLike(String path) {
        return path.endsWith(".yml")
            || path.endsWith(".yaml")
            || path.endsWith(".properties")
            || path.endsWith(".json")
            || path.contains("config");
    }

    private boolean isTestPath(String path) {
        return path.contains("/src/test/") || path.endsWith("test.java") || path.endsWith("spec.ts");
    }

    private String normalizedPath(GithubChangedFile file) {
        return file.filename() == null ? "" : file.filename().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private String sanitizeKey(String value) {
        String sanitized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_$./-]+", "_")
            .replaceAll("_+", "_");
        return sanitized.isBlank() ? "file" : sanitized;
    }

    private int distinctFileCount(List<SemanticDiffSegment> segments) {
        Set<String> filenames = new LinkedHashSet<>();
        for (SemanticDiffSegment segment : segments) {
            filenames.add(segment.file().filename());
        }
        return filenames.size();
    }

    private int distinctSourceFileCount(List<GithubChangedFile> files) {
        if (files == null) {
            return 0;
        }
        Set<String> filenames = new LinkedHashSet<>();
        for (GithubChangedFile file : files) {
            filenames.add(file.filename());
        }
        return filenames.size();
    }

    private boolean splitsSameFileScope(List<SemanticDiffSegment> current, SemanticDiffSegment next) {
        return current.stream().anyMatch(segment -> sameFile(segment.file(), next.file())
            && !segment.semanticKey().equals(next.semanticKey()));
    }

    private boolean sameFile(GithubChangedFile left, GithubChangedFile right) {
        String leftPath = left.filename() == null ? "" : left.filename();
        String rightPath = right.filename() == null ? "" : right.filename();
        return leftPath.equals(rightPath);
    }

    private int changedLines(GithubChangedFile file) {
        return safeInt(file.additions()) + safeInt(file.deletions());
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private record SemanticDiffSegment(
        GithubChangedFile file,
        String chunkGroupKey,
        String semanticKey,
        String semanticReason,
        int riskPriority,
        int additions,
        int deletions
    ) {
        int changedLines() {
            return additions + deletions;
        }
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

        static ChunkingPolicy from(ReviewPolicySettings settings) {
            if (settings == null) {
                return defaults();
            }
            return new ChunkingPolicy(
                positive(settings.chunkMaxFiles(), MAX_FILES_PER_CHUNK),
                positive(settings.chunkMaxLines(), MAX_LINES_PER_CHUNK),
                positive(settings.chunkFileThreshold(), LARGE_PR_FILE_THRESHOLD),
                positive(settings.chunkLineThreshold(), LARGE_PR_LINE_THRESHOLD)
            );
        }

        private static int positive(Integer value, int fallback) {
            return value == null || value < 1 ? fallback : value;
        }
    }
}
