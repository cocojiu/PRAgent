package com.repoguard.agent.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.notification.NotificationTextLimiter;
import com.repoguard.agent.notification.delivery.NotificationSendResult;
import com.repoguard.agent.security.SecretCryptoService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AlternativeNotificationAdapterTest {

    private final SecretCryptoService crypto = new SecretCryptoService("alternative-provider-test-key");
    private final WebhookNotificationContentBuilder content = new WebhookNotificationContentBuilder(
        new WebhookNotificationEventTextFormatter(), new WebhookNotificationFieldFormatter()
    );
    private final WebhookNotificationResponseEvaluator evaluator = new WebhookNotificationResponseEvaluator(
        new NotificationTextLimiter()
    );
    private final WebhookNotificationPayloadFactory payloads = new WebhookNotificationPayloadFactory();
    private final WebhookNotificationRequestFactory requests = new WebhookNotificationRequestFactory(crypto);
    private final ExternalHttpResponseReader reader = new ExternalHttpResponseReader();

    @Test
    void supportsFeishuSlackAndEmailGatewayProviders() throws IOException {
        assertProvider(new FeishuNotificationAdapter(RestClient.builder(), content, evaluator, payloads, requests, reader), "FEISHU", "msg_type");
        assertProvider(new SlackNotificationAdapter(RestClient.builder(), content, evaluator, payloads, requests, reader), "SLACK", "text");
        assertProvider(new EmailNotificationAdapter(RestClient.builder(), content, evaluator, payloads, requests, reader), "EMAIL", "subject");
    }

    private void assertProvider(
        com.repoguard.agent.notification.channel.NotificationChannelAdapter adapter,
        String provider,
        String bodyMarker
    ) throws IOException {
        try (WebhookServer server = WebhookServer.start()) {
            NotificationSendResult result = adapter.test(binding(server.url(), provider));
            assertThat(adapter.provider()).isEqualTo(provider);
            assertThat(result.success()).isTrue();
            assertThat(server.body()).contains(bodyMarker);
        }
    }

    private NotificationChannelBinding binding(String url, String provider) {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setProvider(provider);
        binding.setWebhookUrlValue(crypto.encrypt(url));
        return binding;
    }

    private static final class WebhookServer implements AutoCloseable {
        private final HttpServer server;
        private volatile String body = "";

        private WebhookServer(HttpServer server) {
            this.server = server;
        }

        static WebhookServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            WebhookServer result = new WebhookServer(server);
            server.createContext("/notify", result::respond);
            server.start();
            return result;
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/notify"; }
        String body() { return body; }

        private void respond(HttpExchange exchange) throws IOException {
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = "{\"errcode\":0,\"errmsg\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() { server.stop(0); }
    }
}
