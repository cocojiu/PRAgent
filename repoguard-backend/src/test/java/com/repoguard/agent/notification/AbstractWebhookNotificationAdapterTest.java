package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.security.SecretCryptoService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.repoguard.agent.notification.delivery.NotificationSendResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class AbstractWebhookNotificationAdapterTest {

    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final WeComNotificationAdapter adapter = new WeComNotificationAdapter(
        RestClient.builder(),
        secretCryptoService,
        new WebhookNotificationContentBuilder(
            new WebhookNotificationEventTextFormatter(),
            new WebhookNotificationFieldFormatter()
        ),
        new WebhookNotificationResponseEvaluator(new NotificationTextLimiter()),
        new WebhookNotificationPayloadFactory(),
        new WebhookNotificationRequestFactory(secretCryptoService),
        new ExternalHttpResponseReader()
    );

    @Test
    void doPostEvaluatesSuccessfulResponseBodyFromSharedReader() throws IOException {
        try (WebhookServer server = WebhookServer.start(200, "{\"errcode\":0,\"errmsg\":\"ok\"}", null)) {
            NotificationSendResult result = adapter.test(binding(server.url()));

            assertThat(result.success()).isTrue();
            assertThat(result.message()).contains("\"errcode\":0", "\"errmsg\":\"ok\"");
        }
    }

    @Test
    void doPostClassifiesHttpFailureBodyFromSharedReader() throws IOException {
        try (WebhookServer server = WebhookServer.start(
            429,
            "{\"errmsg\":\"rate limited\",\"access_token\":\"raw-token-value\"}",
            "45"
        )) {
            NotificationSendResult result = adapter.test(binding(server.url()));

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains(
                "Webhook HTTP request failed status=429",
                "retryAfter=45",
                "responseBody={\"errmsg\":\"rate limited\",\"access_token\":\"****\"}"
            );
            assertThat(result.message()).doesNotContain("raw-token-value");
        }
    }

    private NotificationChannelBinding binding(String webhookUrl) {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setProvider("WECOM");
        binding.setWebhookUrlValue(secretCryptoService.encrypt(webhookUrl));
        return binding;
    }

    private record WebhookServer(HttpServer server, String url) implements AutoCloseable {

        static WebhookServer start(int status, String body, String retryAfter) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/webhook", exchange -> respond(exchange, status, body, retryAfter));
            server.start();
            return new WebhookServer(server, "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");
        }

        private static void respond(HttpExchange exchange, int status, String body, String retryAfter)
            throws IOException {
            if (retryAfter != null) {
                exchange.getResponseHeaders().add(HttpHeaders.RETRY_AFTER, retryAfter);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
