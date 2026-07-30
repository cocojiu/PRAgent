package com.repoguard.agent.github;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.repoguard.agent.config.ReviewContextProperties;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.review.ChangedFileContext;
import com.repoguard.agent.review.PullRequestChangedFile;
import com.repoguard.agent.review.ReviewFilePolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GithubChangedFileContextLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(GithubChangedFileContextLoader.class);

    private final GithubChangedFileContentReader contentReader;
    private final ReviewContextProperties properties;
    private final ReviewFilePolicy filePolicy;
    private final Cache<ContextCacheKey, ChangedFileContext> cache;
    private final LongSupplier nanoTime;

    @Autowired
    public GithubChangedFileContextLoader(
        GithubChangedFileContentReader contentReader,
        ReviewContextProperties properties,
        ReviewFilePolicy filePolicy
    ) {
        this(contentReader, properties, filePolicy, System::nanoTime);
    }

    GithubChangedFileContextLoader(
        GithubChangedFileContentReader contentReader,
        ReviewContextProperties properties,
        ReviewFilePolicy filePolicy,
        LongSupplier nanoTime
    ) {
        this.contentReader = Objects.requireNonNull(contentReader, "contentReader");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.filePolicy = Objects.requireNonNull(filePolicy, "filePolicy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        int minimumEntryWeight = (int) Math.max(
            1,
            Math.min(
                Integer.MAX_VALUE,
                properties.getCacheMaximumBytes() / properties.getCacheMaximumSize()
            )
        );
        this.cache = Caffeine.newBuilder()
            .maximumWeight(properties.getCacheMaximumBytes())
            .weigher((ContextCacheKey key, ChangedFileContext value) ->
                Math.max(minimumEntryWeight, Math.max(1, value.contentBytes())))
            .expireAfterWrite(Duration.ofSeconds(properties.getCacheTtlSeconds()))
            .build();
    }

    public List<PullRequestChangedFile> load(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        String headSha,
        List<PullRequestChangedFile> files,
        ExternalCallResilience resilience
    ) {
        List<PullRequestChangedFile> safeFiles = files == null ? List.of() : files;
        if (safeFiles.isEmpty()) {
            return List.of();
        }
        long startedAt = nanoTime.getAsLong();
        int requestedFiles = 0;
        int retainedBytes = 0;
        List<PullRequestChangedFile> enriched = new ArrayList<>(safeFiles.size());
        for (PullRequestChangedFile file : safeFiles) {
            ChangedFileContext immediate = immediateStatus(file, headSha);
            if (immediate != null) {
                enriched.add(file.withContext(immediate));
                continue;
            }
            if (!StringUtils.hasText(headSha)) {
                enriched.add(file.withContext(status(
                    file,
                    headSha,
                    ChangedFileContext.Status.UNAVAILABLE,
                    "missing_head_sha"
                )));
                continue;
            }
            if (requestedFiles >= properties.getMaxFiles()) {
                enriched.add(file.withContext(budgetExceeded(file, headSha, "max_files")));
                continue;
            }
            if (elapsedMillis(startedAt) >= properties.getTotalTimeoutMs()) {
                enriched.add(file.withContext(budgetExceeded(file, headSha, "total_timeout")));
                continue;
            }
            requestedFiles++;
            ChangedFileContext context = loadOne(
                settings,
                baseUrl,
                owner,
                repository,
                headSha.trim(),
                file,
                resilience
            );
            if (context.available() && retainedBytes + context.contentBytes() > properties.getMaxTotalBytes()) {
                context = budgetExceeded(file, headSha, "max_total_bytes");
            } else if (context.available()) {
                retainedBytes += context.contentBytes();
            }
            enriched.add(file.withContext(context));
        }
        LOGGER.info(
            "GitHub changed file context load completed repository={}/{} headSha={} operation=github_file_context "
                + "requestedFiles={} retainedBytes={} totalFiles={} durationMs={}",
            owner,
            repository,
            abbreviateSha(headSha),
            requestedFiles,
            retainedBytes,
            safeFiles.size(),
            elapsedMillis(startedAt)
        );
        return List.copyOf(enriched);
    }

    private ChangedFileContext immediateStatus(PullRequestChangedFile file, String headSha) {
        String status = file.status() == null ? "" : file.status().trim().toLowerCase(Locale.ROOT);
        if ("removed".equals(status) || "deleted".equals(status)) {
            return status(file, headSha, ChangedFileContext.Status.DELETED, "deleted_file");
        }
        if (filePolicy.excluded(file.filename())) {
            return status(file, headSha, ChangedFileContext.Status.EXCLUDED, "repository_path_policy");
        }
        if (!filePolicy.requiresFullFileContext(file)) {
            return status(file, headSha, ChangedFileContext.Status.NOT_REQUIRED, "no_contextual_rule_candidate");
        }
        return null;
    }

    private ChangedFileContext loadOne(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        String headSha,
        PullRequestChangedFile file,
        ExternalCallResilience resilience
    ) {
        ContextCacheKey key = new ContextCacheKey(
            baseUrl,
            owner,
            repository,
            headSha,
            file.filename()
        );
        try {
            return cache.get(key, ignored -> fetchedContext(
                settings,
                baseUrl,
                owner,
                repository,
                headSha,
                file,
                resilience
            ));
        } catch (RuntimeException ex) {
            LOGGER.warn(
                "GitHub changed file context unavailable repository={}/{} headSha={} file={} "
                    + "operation=github_file_context result=unavailable exceptionType={}",
                owner,
                repository,
                abbreviateSha(headSha),
                file.filename(),
                ex.getClass().getName()
            );
            return status(
                file,
                headSha,
                ChangedFileContext.Status.UNAVAILABLE,
                "fetch_failed:" + ex.getClass().getSimpleName()
            );
        }
    }

    private ChangedFileContext fetchedContext(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        String headSha,
        PullRequestChangedFile file,
        ExternalCallResilience resilience
    ) {
        String content = contentReader.fetch(
            settings,
            baseUrl,
            owner,
            repository,
            headSha,
            file.filename(),
            resilience
        );
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.getMaxFileBytes()) {
            return status(file, headSha, ChangedFileContext.Status.TOO_LARGE, "max_file_bytes");
        }
        if (content.indexOf('\0') >= 0 || content.indexOf('\uFFFD') >= 0) {
            return status(file, headSha, ChangedFileContext.Status.BINARY, "non_text_content");
        }
        return ChangedFileContext.available(file.filename(), headSha, content);
    }

    private ChangedFileContext budgetExceeded(PullRequestChangedFile file, String headSha, String reason) {
        return status(file, headSha, ChangedFileContext.Status.BUDGET_EXCEEDED, reason);
    }

    private ChangedFileContext status(
        PullRequestChangedFile file,
        String headSha,
        ChangedFileContext.Status status,
        String reason
    ) {
        return ChangedFileContext.status(file.filename(), headSha, status, reason);
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanoTime.getAsLong() - startedAt));
    }

    private String abbreviateSha(String headSha) {
        if (!StringUtils.hasText(headSha)) {
            return "unavailable";
        }
        String trimmed = headSha.trim();
        return trimmed.length() <= 12 ? trimmed : trimmed.substring(0, 12);
    }

    private record ContextCacheKey(
        String baseUrl,
        String owner,
        String repository,
        String headSha,
        String filePath
    ) {
    }
}
