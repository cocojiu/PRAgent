package com.repoguard.agent.github;

import com.repoguard.agent.config.GithubDiffBudgetProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

final class GithubChangedFileBudgetAccumulator {

    private static final int ESTIMATED_JSON_OVERHEAD_BYTES = 48;

    private final GithubDiffBudgetProperties properties;
    private final LongSupplier nanoTime;
    private final long deadlineNanos;
    private final List<GithubChangedFile> files = new ArrayList<>();
    private final EnumSet<GithubDiffTruncation.Reason> reasons =
        EnumSet.noneOf(GithubDiffTruncation.Reason.class);
    private long retainedBytes;

    GithubChangedFileBudgetAccumulator(GithubDiffBudgetProperties properties) {
        this(properties, System::nanoTime);
    }

    GithubChangedFileBudgetAccumulator(
        GithubDiffBudgetProperties properties,
        LongSupplier nanoTime
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        long timeoutNanos = this.properties.getTotalTimeoutMs() * 1_000_000L;
        long now = nanoTime.getAsLong();
        this.deadlineNanos = Long.MAX_VALUE - now < timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
    }

    boolean acceptPage(List<GithubChangedFile> pageItems, boolean sourceHasMore) {
        if (deadlineExceeded()) {
            reasons.add(GithubDiffTruncation.Reason.TOTAL_TIMEOUT);
            return false;
        }
        for (GithubChangedFile file : pageItems) {
            if (deadlineExceeded()) {
                reasons.add(GithubDiffTruncation.Reason.TOTAL_TIMEOUT);
                return false;
            }
            if (files.size() >= properties.getMaxFiles()) {
                reasons.add(GithubDiffTruncation.Reason.MAX_FILES);
                return false;
            }
            GithubChangedFile retained = retainWithinPatchBudget(file);
            long fileBytes = retainedBytes(retained);
            if (fileBytes > properties.getMaxTotalBytes() - retainedBytes) {
                reasons.add(GithubDiffTruncation.Reason.MAX_TOTAL_BYTES);
                return false;
            }
            files.add(retained);
            retainedBytes += fileBytes;
        }
        if (sourceHasMore && files.size() >= properties.getMaxFiles()) {
            reasons.add(GithubDiffTruncation.Reason.MAX_FILES);
            return false;
        }
        if (sourceHasMore && retainedBytes >= properties.getMaxTotalBytes()) {
            reasons.add(GithubDiffTruncation.Reason.MAX_TOTAL_BYTES);
            return false;
        }
        if (deadlineExceeded()) {
            reasons.add(GithubDiffTruncation.Reason.TOTAL_TIMEOUT);
            return false;
        }
        return true;
    }

    private boolean deadlineExceeded() {
        return nanoTime.getAsLong() >= deadlineNanos;
    }

    GithubChangedFileFetch finish(GithubPaginator.PageTraversal traversal) {
        if (traversal.pageLimitReached()) {
            reasons.add(GithubDiffTruncation.Reason.MAX_PAGES);
        }
        return new GithubChangedFileFetch(
            files,
            new GithubDiffTruncation(
                List.copyOf(reasons),
                traversal.pagesFetched(),
                files.size(),
                retainedBytes
            )
        );
    }

    private GithubChangedFile retainWithinPatchBudget(GithubChangedFile file) {
        String patch = file == null ? null : file.patch();
        String retainedPatch = truncateUtf8(patch, properties.getMaxPatchBytes());
        if (patch != null && !patch.equals(retainedPatch)) {
            reasons.add(GithubDiffTruncation.Reason.MAX_PATCH_BYTES);
        }
        if (file == null) {
            return new GithubChangedFile(null, null, null, null, retainedPatch);
        }
        return new GithubChangedFile(
            file.filename(),
            file.status(),
            file.additions(),
            file.deletions(),
            retainedPatch
        );
    }

    private long retainedBytes(GithubChangedFile file) {
        return ESTIMATED_JSON_OVERHEAD_BYTES
            + utf8Length(file.filename())
            + utf8Length(file.status())
            + utf8Length(file.patch());
    }

    private int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String truncateUtf8(String value, int maxBytes) {
        if (value == null || utf8Length(value) <= maxBytes) {
            return value;
        }
        int bytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int codePointBytes = utf8Bytes(codePoint);
            if (bytes + codePointBytes > maxBytes) {
                break;
            }
            bytes += codePointBytes;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }
}
