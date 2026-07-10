package com.repoguard.agent.observability;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ObservationPathNormalizer {

    private static final int MAX_FRONTEND_PATH_LENGTH = 80;
    private static final int MAX_API_PATH_LENGTH = 256;
    private static final String UNKNOWN_FRONTEND_PATH = "unknown";
    private static final String UNKNOWN_API_PATH = "/api/v1/unknown";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("\\d+");
    private static final Pattern UUID_SEGMENT = Pattern.compile(
        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    );
    private static final Pattern HEX_IDENTIFIER_SEGMENT = Pattern.compile("(?i)[0-9a-f]+");
    private static final int MIN_FRONTEND_HASH_LENGTH = 7;
    private static final int MIN_API_HASH_LENGTH = 32;

    public String normalizeFrontendPath(String path) {
        return normalize(path, UNKNOWN_FRONTEND_PATH, MAX_FRONTEND_PATH_LENGTH, MIN_FRONTEND_HASH_LENGTH, false);
    }

    public String normalizeApiPath(String path) {
        return normalize(path, UNKNOWN_API_PATH, MAX_API_PATH_LENGTH, MIN_API_HASH_LENGTH, true);
    }

    private String normalize(
        String path,
        String fallback,
        int maxLength,
        int minHashLength,
        boolean bracedPlaceholders
    ) {
        if (!StringUtils.hasText(path)) {
            return fallback;
        }
        String pathOnly = extractPath(WHITESPACE.matcher(path.trim()).replaceAll("_"));
        if (!StringUtils.hasText(pathOnly)) {
            return fallback;
        }
        String[] segments = pathOnly.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            segments[index] = normalizeSegment(segments[index], minHashLength, bracedPlaceholders);
        }
        String normalized = String.join("/", segments);
        if (!StringUtils.hasText(normalized) || ("/".equals(normalized) && UNKNOWN_API_PATH.equals(fallback))) {
            return fallback;
        }
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private String extractPath(String value) {
        try {
            URI uri = new URI(value);
            String rawPath = uri.getRawPath();
            if (StringUtils.hasText(rawPath)) {
                return rawPath;
            }
            if (uri.isAbsolute() || uri.getRawAuthority() != null) {
                return "/";
            }
        } catch (URISyntaxException ignored) {
            // Spring route patterns contain braces, so invalid URI syntax uses the string fallback.
        }
        return stripQueryAndFragment(stripOrigin(value));
    }

    private String stripOrigin(String value) {
        int schemeIndex = value.indexOf("://");
        if (schemeIndex >= 0) {
            int pathStart = value.indexOf('/', schemeIndex + 3);
            return pathStart < 0 ? "/" : value.substring(pathStart);
        }
        if (value.startsWith("//")) {
            int pathStart = value.indexOf('/', 2);
            return pathStart < 0 ? "/" : value.substring(pathStart);
        }
        return value;
    }

    private String stripQueryAndFragment(String value) {
        int queryIndex = value.indexOf('?');
        int fragmentIndex = value.indexOf('#');
        int suffixIndex;
        if (queryIndex < 0) {
            suffixIndex = fragmentIndex;
        } else if (fragmentIndex < 0) {
            suffixIndex = queryIndex;
        } else {
            suffixIndex = Math.min(queryIndex, fragmentIndex);
        }
        return suffixIndex < 0 ? value : value.substring(0, suffixIndex);
    }

    private String normalizeSegment(String segment, int minHashLength, boolean bracedPlaceholders) {
        if (!StringUtils.hasText(segment)) {
            return segment;
        }
        String value = segment.trim();
        if (NUMERIC_SEGMENT.matcher(value).matches()) {
            return placeholder("id", bracedPlaceholders);
        }
        if (UUID_SEGMENT.matcher(value).matches()) {
            return placeholder("uuid", bracedPlaceholders);
        }
        if (value.length() >= minHashLength && HEX_IDENTIFIER_SEGMENT.matcher(value).matches()) {
            return placeholder("hash", bracedPlaceholders);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String placeholder(String value, boolean braced) {
        return braced ? "{" + value + "}" : value;
    }
}
