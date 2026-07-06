package com.repoguard.agent.review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClientResponseException;

public class LlmHttpResponseReader {

    public byte[] readSuccessfulBody(ClientHttpResponse response, String failureMessagePrefix) throws IOException {
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
