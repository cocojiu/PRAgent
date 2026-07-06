package com.repoguard.agent.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

public class ApiRequestObservationFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/v1";
    private static final String UNKNOWN_PATH = "/api/v1/unknown";

    private final RepoGuardMetrics metrics;
    private final ObservabilityThresholdMonitor thresholdMonitor;

    public ApiRequestObservationFilter(RepoGuardMetrics metrics) {
        this(metrics, new ObservabilityThresholdMonitor(metrics, new ObservabilityThresholdProperties()));
    }

    public ApiRequestObservationFilter(RepoGuardMetrics metrics, ObservabilityThresholdMonitor thresholdMonitor) {
        this.metrics = metrics;
        this.thresholdMonitor = thresholdMonitor;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = apiPath(request);
        return !path.startsWith(API_PREFIX + "/") && !path.equals(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        CountingHttpServletResponse responseWrapper = new CountingHttpServletResponse(response);
        long startNanos = System.nanoTime();
        Throwable failure = null;
        try {
            filterChain.doFilter(request, responseWrapper);
        } catch (ServletException | IOException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            String path = observationPath(request);
            int status = effectiveStatus(responseWrapper.getStatus(), failure);
            String outcome = outcome(responseWrapper.getStatus(), failure);
            long responseBytes = responseWrapper.bodyBytes();
            metrics.apiRequest(
                duration,
                request.getMethod(),
                path,
                status,
                outcome,
                responseBytes
            );
            thresholdMonitor.apiRequest(duration, path, responseBytes);
        }
    }

    private String observationPath(HttpServletRequest request) {
        Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatchingPattern instanceof String pattern && StringUtils.hasText(pattern)) {
            return pattern;
        }
        return normalizeDynamicPath(apiPath(request));
    }

    private String apiPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!StringUtils.hasText(requestUri)) {
            return "";
        }
        if (StringUtils.hasText(contextPath) && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    private String normalizeDynamicPath(String path) {
        if (!StringUtils.hasText(path)) {
            return UNKNOWN_PATH;
        }
        String[] segments = path.split("/");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            normalized.append('/').append(normalizeSegment(segment));
        }
        return normalized.isEmpty() ? UNKNOWN_PATH : normalized.toString();
    }

    private String normalizeSegment(String segment) {
        String value = segment.trim();
        if (value.matches("\\d+")) {
            return "{id}";
        }
        if (value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return "{uuid}";
        }
        if (value.matches("(?i)[0-9a-f]{32,64}")) {
            return "{hash}";
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private int effectiveStatus(int status, Throwable failure) {
        if (failure != null && status < HttpServletResponse.SC_BAD_REQUEST) {
            return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
        return status;
    }

    private String outcome(int status, Throwable failure) {
        int effectiveStatus = effectiveStatus(status, failure);
        if (effectiveStatus >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
            return "server_error";
        }
        if (effectiveStatus >= HttpServletResponse.SC_BAD_REQUEST) {
            return "client_error";
        }
        if (effectiveStatus >= HttpServletResponse.SC_MULTIPLE_CHOICES) {
            return "redirection";
        }
        return "success";
    }

    private static class CountingHttpServletResponse extends HttpServletResponseWrapper {

        private final AtomicLong bodyBytes = new AtomicLong();
        private ServletOutputStream outputStream;
        private PrintWriter writer;

        CountingHttpServletResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (outputStream == null) {
                outputStream = new CountingServletOutputStream(super.getOutputStream(), bodyBytes);
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                writer = new PrintWriter(new CountingWriter(super.getWriter(), bodyBytes, responseCharset()), false);
            }
            return writer;
        }

        long bodyBytes() {
            return bodyBytes.get();
        }

        private Charset responseCharset() {
            String encoding = getCharacterEncoding();
            if (!StringUtils.hasText(encoding)) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(encoding);
            } catch (IllegalArgumentException ex) {
                return StandardCharsets.UTF_8;
            }
        }
    }

    private static class CountingWriter extends Writer {

        private final Writer delegate;
        private final AtomicLong bodyBytes;
        private final Charset charset;

        CountingWriter(Writer delegate, AtomicLong bodyBytes, Charset charset) {
            this.delegate = delegate;
            this.bodyBytes = bodyBytes;
            this.charset = charset;
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            bodyBytes.addAndGet(String.valueOf((char) value).getBytes(charset).length);
        }

        @Override
        public void write(char[] values, int offset, int length) throws IOException {
            delegate.write(values, offset, length);
            bodyBytes.addAndGet(new String(values, offset, length).getBytes(charset).length);
        }

        @Override
        public void write(String value, int offset, int length) throws IOException {
            delegate.write(value, offset, length);
            bodyBytes.addAndGet(value.substring(offset, offset + length).getBytes(charset).length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static class CountingServletOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;
        private final AtomicLong bodyBytes;

        CountingServletOutputStream(ServletOutputStream delegate, AtomicLong bodyBytes) {
            this.delegate = delegate;
            this.bodyBytes = bodyBytes;
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            bodyBytes.incrementAndGet();
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            delegate.write(values, offset, length);
            bodyBytes.addAndGet(Math.max(0, length));
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
