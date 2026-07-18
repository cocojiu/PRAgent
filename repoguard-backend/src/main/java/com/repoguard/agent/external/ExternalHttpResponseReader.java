package com.repoguard.agent.external;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ExternalHttpResponseReader {

    private final MeterRegistry meterRegistry;

    public ExternalHttpResponseReader() {
        this.meterRegistry = null;
    }

    @Autowired
    public ExternalHttpResponseReader(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    public byte[] readSuccessfulBody(
        ClientHttpResponse response,
        String failureMessagePrefix,
        ExternalHttpResponseProfile profile
    ) throws IOException {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(failureMessagePrefix, "failureMessagePrefix");
        Objects.requireNonNull(profile, "profile");
        int maxBytes = profile.maxBytes();
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > maxBytes) {
            throw rejectTooLarge(
                response,
                failureMessagePrefix,
                profile,
                contentLength,
                "content_length"
            );
        }
        byte[] body = response.getBody().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            throw rejectTooLarge(
                response,
                failureMessagePrefix,
                profile,
                body.length,
                "stream"
            );
        }
        if (!response.getStatusCode().isError()) {
            return body;
        }
        throw new RestClientResponseException(
            failureMessagePrefix + " with HTTP status " + response.getStatusCode().value(),
            response.getStatusCode().value(),
            response.getStatusText(),
            response.getHeaders(),
            body,
            StandardCharsets.UTF_8
        );
    }

    private ExternalHttpResponseTooLargeException rejectTooLarge(
        ClientHttpResponse response,
        String failureMessagePrefix,
        ExternalHttpResponseProfile profile,
        long observedBytes,
        String detection
    ) {
        response.close();
        if (meterRegistry != null) {
            meterRegistry.counter(
                "repoguard.external.response.too_large",
                "profile", profile.metricTag(),
                "detection", detection
            ).increment();
        }
        return new ExternalHttpResponseTooLargeException(
            failureMessagePrefix,
            profile,
            profile.maxBytes(),
            observedBytes,
            detection
        );
    }
}
