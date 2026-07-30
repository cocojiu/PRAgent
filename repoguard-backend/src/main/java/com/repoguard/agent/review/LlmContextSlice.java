package com.repoguard.agent.review;

import java.util.Set;

record LlmContextSlice(
    String filePath,
    int startLine,
    int endLine,
    Role role,
    String numberedContent,
    Set<String> symbols,
    int riskPriority
) {

    enum Role {
        SOURCE,
        INTERFACE,
        DIRECT_CALLER,
        TEST,
        CONFIG
    }

    LlmContextSlice {
        numberedContent = numberedContent == null ? "" : numberedContent;
        symbols = symbols == null ? Set.of() : Set.copyOf(symbols);
    }

    String render(Role renderedRole) {
        return "[" + renderedRole.name() + "] " + filePath + ":L" + startLine + "-L" + endLine
            + "\n" + numberedContent;
    }
}
