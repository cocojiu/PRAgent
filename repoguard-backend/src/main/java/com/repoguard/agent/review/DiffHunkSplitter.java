package com.repoguard.agent.review;

import java.util.ArrayList;
import java.util.List;

class DiffHunkSplitter {

    List<String> split(String patch) {
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

    int countPatchLines(String patch, char marker) {
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

    int allocatedLines(int visibleLines, int totalVisibleLines, int sourceLines, int alreadyAllocated, boolean last) {
        if (sourceLines == 0) {
            return visibleLines;
        }
        if (last || totalVisibleLines == 0) {
            return Math.max(0, sourceLines - alreadyAllocated);
        }
        return Math.max(0, (int) Math.round((double) visibleLines * sourceLines / totalVisibleLines));
    }
}
