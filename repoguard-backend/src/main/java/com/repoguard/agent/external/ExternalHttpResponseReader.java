package com.repoguard.agent.external;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ExternalHttpResponseReader {

    public byte[] readSuccessfulBody(ClientHttpResponse response, String failureMessagePrefix) throws IOException {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(failureMessagePrefix, "failureMessagePrefix");
        byte[] body = response.getBody().readAllBytes();
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
}
