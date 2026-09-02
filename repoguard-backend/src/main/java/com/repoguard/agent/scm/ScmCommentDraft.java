package com.repoguard.agent.scm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ScmCommentDraft(
    Long findingId,
    @Size(max = 1024) String path,
    Integer line,
    @NotBlank @Size(max = 64_000) String body
) {
}
