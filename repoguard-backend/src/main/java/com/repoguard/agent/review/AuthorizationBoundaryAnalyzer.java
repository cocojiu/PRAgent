package com.repoguard.agent.review;

import java.util.Locale;
import java.util.regex.Pattern;

final class AuthorizationBoundaryAnalyzer {

    private static final Pattern AUTHORIZATION_GUARD = Pattern.compile(
        "@(?:RequireRole|RequirePermission|PreAuthorize|PostAuthorize|Secured|RolesAllowed"
            + "|RequiresRoles|RequiresPermissions|CheckPermission|Authorize)\\b"
    );
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
        "\\b(?:class|interface)\\s+[A-Za-z_$][A-Za-z0-9_$]*"
    );
    private static final Pattern INHERITED_BOUNDARY = Pattern.compile(
        "(?i)\\b(?:extends|implements)\\s+[^\\n{]*(?:secured|authorized|protected|permission|role)[A-Za-z0-9_$,.<> ]*"
    );

    boolean hasApplicableBoundary(String source, int changedLine, String mappingLine) {
        if (source == null || source.isBlank()) {
            return false;
        }
        if (classLevelGuard(source) || INHERITED_BOUNDARY.matcher(source).find()) {
            return true;
        }
        String[] lines = source.split("\\R", -1);
        int mappingIndex = mappingIndex(lines, changedLine, mappingLine);
        if (mappingIndex < 0) {
            return false;
        }
        int from = Math.max(0, mappingIndex - 12);
        int to = Math.min(lines.length - 1, mappingIndex + 8);
        StringBuilder declarationWindow = new StringBuilder();
        for (int index = from; index <= to; index++) {
            declarationWindow.append(lines[index]).append('\n');
            if (index > mappingIndex && lines[index].contains("{")) {
                break;
            }
        }
        return AUTHORIZATION_GUARD.matcher(declarationWindow).find();
    }

    private boolean classLevelGuard(String source) {
        var declaration = CLASS_DECLARATION.matcher(source);
        if (!declaration.find()) {
            return false;
        }
        int annotationWindowStart = Math.max(0, source.lastIndexOf('}', declaration.start()) + 1);
        String prefix = source.substring(annotationWindowStart, declaration.start());
        return AUTHORIZATION_GUARD.matcher(prefix).find();
    }

    private int mappingIndex(String[] lines, int changedLine, String mappingLine) {
        int expected = changedLine - 1;
        if (expected >= 0 && expected < lines.length && mutatingMapping(lines[expected])) {
            return expected;
        }
        String target = mappingLine == null ? "" : mappingLine.trim();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if ((!target.isBlank() && line.equals(target)) || mutatingMapping(line)) {
                return index;
            }
        }
        return -1;
    }

    private boolean mutatingMapping(String line) {
        String normalized = line == null ? "" : line.toLowerCase(Locale.ROOT);
        return normalized.contains("@postmapping")
            || normalized.contains("@putmapping")
            || normalized.contains("@patchmapping")
            || normalized.contains("@deletemapping");
    }
}
