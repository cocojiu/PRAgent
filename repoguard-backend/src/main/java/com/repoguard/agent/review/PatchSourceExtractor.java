package com.repoguard.agent.review;

final class PatchSourceExtractor {

    private PatchSourceExtractor() {
    }

    static String afterImage(String patch, String fallbackLine) {
        if (patch == null || patch.isBlank() || !patch.contains("@@")) {
            return fallbackLine == null ? "" : fallbackLine;
        }
        StringBuilder source = new StringBuilder();
        for (String patchLine : patch.split("\\R", -1)) {
            if (patchLine.startsWith("@@") || patchLine.startsWith("---") || patchLine.startsWith("+++")) {
                continue;
            }
            if (patchLine.startsWith("-")) {
                continue;
            }
            if (patchLine.startsWith("+") || patchLine.startsWith(" ")) {
                source.append(patchLine.substring(1));
            } else {
                source.append(patchLine);
            }
            source.append('\n');
        }
        return source.toString();
    }
}
