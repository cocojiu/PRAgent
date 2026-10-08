package com.repoguard.agent.review.codeowners;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/** Bounded CODEOWNERS subset. Unsupported syntax suppresses recommendations, never guesses owners. */
public final class CodeownersRules {
    private CodeownersRules() { }

    public record Match(String path, String pattern, int line, List<String> owners) { }
    public record Resolution(String status, List<Match> paths, List<Integer> invalidLines) { }
    private record Rule(String pattern, int line, List<String> owners) { }

    public static Resolution resolve(String content, List<String> paths) {
        if (content == null) return new Resolution("MISSING", List.of(), List.of());
        if (content.length() > 131072 || content.getBytes(StandardCharsets.UTF_8).length > 131072
            || paths == null || paths.size() > 200) return unavailable("BUDGET_EXCEEDED");
        String[] lines = content.split("\\R", -1);
        if (content.lines().limit(2001).count() > 2000) return unavailable("BUDGET_EXCEEDED");
        List<Rule> rules = new ArrayList<>();
        List<Integer> invalid = new ArrayList<>();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n].split("#", 2)[0].strip();
            if (line.isEmpty()) continue;
            String[] columns = line.split("\\s+");
            String pattern = columns[0];
            List<String> owners = Arrays.asList(columns).subList(1, columns.length);
            if (!validPattern(pattern) || owners.size() > 20 || owners.stream().anyMatch(owner ->
                owner.length() > 255 || !owner.matches("@[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)?|[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
                invalid.add(n + 1);
            } else rules.add(new Rule(pattern, n + 1, List.copyOf(new LinkedHashSet<>(owners))));
        }
        if (!invalid.isEmpty()) return new Resolution("UNSUPPORTED_SYNTAX", List.of(), List.copyOf(invalid));
        List<Match> matches = new ArrayList<>();
        int[] work = {2_000_000};
        try {
            for (String path : new LinkedHashSet<>(paths)) {
                if (!validPath(path)) return unavailable("INVALID_PATH");
                Match match = new Match(path, null, 0, List.of());
                for (Rule rule : rules) if (matches(rule.pattern(), path, work)) {
                    match = new Match(path, rule.pattern(), rule.line(), rule.owners());
                }
                matches.add(match);
            }
        } catch (WorkLimit failure) { return unavailable("MATCH_BUDGET_EXCEEDED"); }
        return new Resolution("RESOLVED", List.copyOf(matches), List.of());
    }

    private static Resolution unavailable(String status) { return new Resolution(status, List.of(), List.of()); }
    private static boolean validPath(String path) {
        return path != null && !path.isBlank() && path.length() <= 512 && !path.startsWith("/")
            && !path.endsWith("/") && !path.contains("\\") && !path.contains("//")
            && path.chars().noneMatch(Character::isISOControl) && path.split("/").length <= 32
            && Arrays.stream(path.split("/")).noneMatch(part -> part.equals(".") || part.equals(".."));
    }
    private static boolean validPattern(String value) {
        return value.length() <= 256 && !value.equals("/") && !value.contains("//")
            && value.chars().noneMatch(c -> "![]\\".indexOf(c) >= 0 || Character.isISOControl(c))
            && value.split("/").length <= 32
            && Arrays.stream(value.split("/")).noneMatch(part -> part.equals(".") || part.equals("..")
                || (part.contains("**") && !part.equals("**")));
    }
    private static boolean matches(String pattern, String path, int[] work) {
        boolean directory = pattern.endsWith("/");
        String stripped = pattern.replaceAll("^/|/$", "");
        List<String> tokens = new ArrayList<>(Arrays.asList(stripped.split("/")));
        if (!pattern.startsWith("/") && !stripped.contains("/")) tokens.addFirst("**");
        if (stripped.endsWith("/**")) tokens.add("*"); // A trailing /** requires a descendant.
        String[] parts = path.split("/");
        boolean[][] reachable = new boolean[tokens.size() + 1][parts.length + 1];
        reachable[0][0] = true;
        for (int i = 0; i < tokens.size(); i++) {
            for (int j = 0; j <= parts.length; j++) {
                tick(work);
                if (!reachable[i][j]) continue;
                if (tokens.get(i).equals("**")) {
                    reachable[i + 1][j] = true;
                    if (j < parts.length) reachable[i][j + 1] = true;
                } else if (j < parts.length && segment(tokens.get(i), parts[j], work)) reachable[i + 1][j + 1] = true;
            }
        }
        if (!directory && reachable[tokens.size()][parts.length]) return true;
        String last = tokens.getLast();
        if (directory || (!last.contains("*") && !last.contains("?"))) {
            for (int j = 0; j < parts.length; j++) if (reachable[tokens.size()][j]) return true;
        }
        return false;
    }
    private static boolean segment(String pattern, String name, int[] work) {
        boolean[] row = new boolean[name.length() + 1]; row[0] = true;
        for (char token : pattern.toCharArray()) {
            boolean[] next = new boolean[row.length]; next[0] = token == '*' && row[0];
            for (int j = 1; j < row.length; j++) {
                tick(work);
                next[j] = token == '*' ? row[j] || next[j - 1] : row[j - 1] && (token == '?' || token == name.charAt(j - 1));
            }
            row = next;
        }
        return row[name.length()];
    }
    private static void tick(int[] work) { if (--work[0] < 0) throw new WorkLimit(); }
    private static final class WorkLimit extends RuntimeException { private static final long serialVersionUID = 1L; }
}
