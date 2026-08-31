package com.repoguard.agent.observability;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Adds only the standard traceparent header to outbound HTTP calls. */
public final class W3CTracePropagationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(
        HttpRequest request,
        byte[] body,
        ClientHttpRequestExecution execution
    ) throws IOException {
        TracePropagation.inject(request.getHeaders());
        return execution.execute(request, body);
    }
}
