package com.repoguard.agent.github;

import java.util.List;
import java.util.Locale;

public record GithubDiffTruncation(
    List<Reason> reasons,
    int pagesFetched,
    int filesReturned,
    long retainedBytes
) {

    public GithubDiffTruncation {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        pagesFetched = Math.max(0, pagesFetched);
        filesReturned = Math.max(0, filesReturned);
        retainedBytes = Math.max(0L, retainedBytes);
    }

    public static GithubDiffTruncation none() {
        return new GithubDiffTruncation(List.of(), 0, 0, 0L);
    }

    public boolean truncated() {
        return !reasons.isEmpty();
    }

    public String summary() {
        String reasonCodes = reasons.stream()
            .map(Reason::code)
            .distinct()
            .reduce((left, right) -> left + "," + right)
            .orElse("none");
        return "GitHub diff truncated: reasons=" + reasonCodes
            + ", pages=" + pagesFetched
            + ", files=" + filesReturned
            + ", retainedBytes=" + retainedBytes;
    }

    public enum Reason {
        MAX_PAGES,
        MAX_FILES,
        MAX_TOTAL_BYTES,
        MAX_PATCH_BYTES,
        TOTAL_TIMEOUT;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
