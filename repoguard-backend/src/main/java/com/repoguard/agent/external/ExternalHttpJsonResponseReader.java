package com.repoguard.agent.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

@Component
public class ExternalHttpJsonResponseReader {

    private final ObjectMapper objectMapper;
    private final ExternalHttpResponseReader responseReader;

    public ExternalHttpJsonResponseReader(ObjectMapper objectMapper, ExternalHttpResponseReader responseReader) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.responseReader = Objects.requireNonNull(responseReader, "responseReader");
    }

    public <T> T readSuccessfulJson(
        ClientHttpResponse response,
        Class<T> responseType,
        String failureMessagePrefix
    ) throws IOException {
        Objects.requireNonNull(responseType, "responseType");
        byte[] body = responseReader.readSuccessfulBody(response, failureMessagePrefix);
        return body == null || body.length == 0 ? null : objectMapper.readValue(body, responseType);
    }
}
