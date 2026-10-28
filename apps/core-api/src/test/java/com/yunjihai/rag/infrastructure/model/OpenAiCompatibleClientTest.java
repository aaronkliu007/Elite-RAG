package com.yunjihai.rag.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    void postsOnlyToFixedPathWithServerCredential() throws Exception {
        server.stubFor(post("/v1/embeddings")
                .withHeader("Authorization", equalTo("Bearer sk-sensitive-key"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withHeader("x-request-id", "req-1")
                        .withBody("{\"data\":[]}")));

        try (var client = client(Duration.ofSeconds(2))) {
            var response = client.post(
                    ModelEndpoint.EMBEDDINGS,
                    JSON.readTree("{\"model\":\"embedding-model\",\"input\":[\"hello\"]}"));

            assertThat(response.body().path("data").isArray()).isTrue();
            assertThat(response.providerRequestId()).isEqualTo("req-1");
            assertThat(response.latency()).isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(response.toString())
                    .contains("body=<redacted>")
                    .doesNotContain("data");
        }
        server.verify(1, postRequestedFor(urlEqualTo("/v1/embeddings")));
    }

    @Test
    void refusesRedirectWithoutContactingTarget() throws Exception {
        server.stubFor(post("/v1/embeddings")
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/redirect-target")));
        server.stubFor(post("/redirect-target").willReturn(aResponse().withBody("{}")));

        try (var client = client(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> client.post(ModelEndpoint.EMBEDDINGS, JSON.readTree("{}")))
                    .isInstanceOf(ModelGatewayException.class)
                    .extracting("kind")
                    .isEqualTo(ModelGatewayException.Kind.POLICY_VIOLATION);
        }
        server.verify(0, postRequestedFor(urlEqualTo("/redirect-target")));
    }

    @Test
    void normalizesAuthenticationRateLimitAndInvalidJsonWithoutLeaks() throws Exception {
        server.stubFor(post("/v1/chat/completions")
                .willReturn(aResponse().withStatus(401).withBody("question-secret sk-sensitive-key")));

        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleClient.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            try (var client = client(Duration.ofSeconds(2))) {
                assertThatThrownBy(() -> client.post(
                        ModelEndpoint.CHAT_COMPLETIONS,
                        JSON.readTree("{\"question\":\"question-secret\"}")))
                        .isInstanceOf(ModelGatewayException.class)
                        .hasMessage("MODEL_GATEWAY_AUTHENTICATION")
                        .hasMessageNotContaining("question-secret")
                        .hasMessageNotContaining("sk-sensitive-key");
            }
            assertThat(logs.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allMatch(message -> !message.contains("question-secret")
                            && !message.contains("sk-sensitive-key"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }

        server.resetAll();
        server.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(429)));
        try (var client = client(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> client.post(ModelEndpoint.CHAT_COMPLETIONS, JSON.readTree("{}")))
                    .isInstanceOf(ModelGatewayException.class)
                    .extracting("kind")
                    .isEqualTo(ModelGatewayException.Kind.RATE_LIMITED);
        }
        server.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));

        server.resetAll();
        server.stubFor(post("/v1/chat/completions")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("not-json")));
        try (var client = client(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> client.post(ModelEndpoint.CHAT_COMPLETIONS, JSON.readTree("{}")))
                    .isInstanceOf(ModelGatewayException.class)
                    .extracting("kind")
                    .isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);
        }
    }

    @Test
    void retriesServerFailureOnlyOnceAndNormalizesIt() throws Exception {
        server.stubFor(post("/v1/rerank").willReturn(aResponse().withStatus(503)));

        try (var client = client(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> client.post(ModelEndpoint.RERANK, JSON.readTree("{}")))
                    .isInstanceOfSatisfying(ModelGatewayException.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(ModelGatewayException.Kind.UNAVAILABLE);
                        assertThat(failure.retryCount()).isEqualTo(1);
                    });
        }
        server.verify(2, postRequestedFor(urlEqualTo("/v1/rerank")));
        var requestIds = server.getAllServeEvents().stream()
                .map(event -> event.getRequest().getHeader("X-Request-Id"))
                .toList();
        assertThat(requestIds).hasSize(2).doesNotContainNull();
        assertThat(requestIds).containsOnly(requestIds.getFirst());
    }

    @Test
    void reportsOneRetryWhenTheSecondAttemptSucceeds() throws Exception {
        server.stubFor(post("/v1/rerank")
                .inScenario("successful-retry")
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("retry")
                .willReturn(aResponse().withStatus(503)));
        server.stubFor(post("/v1/rerank")
                .inScenario("successful-retry")
                .whenScenarioStateIs("retry")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        try (var client = client(Duration.ofSeconds(2))) {
            assertThat(client.post(ModelEndpoint.RERANK, JSON.readTree("{}"))
                    .retryCount()).isEqualTo(1);
        }
        server.verify(2, postRequestedFor(urlEqualTo("/v1/rerank")));
    }

    @Test
    void usesUtf8UpperBoundBeforeSendingModelInput() throws Exception {
        var properties = properties(Duration.ofSeconds(2), Duration.ofSeconds(3), 12, 1, 13);
        try (var client = new OpenAiCompatibleClient(properties, JSON, new ModelUsagePolicy(properties))) {
            assertThatThrownBy(() -> client.post(
                    ModelEndpoint.EMBEDDINGS,
                    JSON.readTree("{\"input\":[\"这是中文内容\"]}")))
                    .isInstanceOf(ModelGatewayException.class)
                    .extracting("kind")
                    .isEqualTo(ModelGatewayException.Kind.POLICY_VIOLATION);
        }
        server.verify(0, postRequestedFor(urlEqualTo("/v1/embeddings")));
    }

    @Test
    void appliesOneTotalDeadlineAcrossBothAttempts() throws Exception {
        server.stubFor(post("/v1/rerank")
                .inScenario("deadline")
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("retry")
                .willReturn(aResponse().withStatus(503).withFixedDelay(300)));
        server.stubFor(post("/v1/rerank")
                .inScenario("deadline")
                .whenScenarioStateIs("retry")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(300)
                        .withBody("{}")));
        var properties = properties(Duration.ofMillis(400), Duration.ofMillis(500), 8_000, 1_000, 100_000);
        long started = System.nanoTime();
        try (var client = new OpenAiCompatibleClient(properties, JSON, new ModelUsagePolicy(properties))) {
            assertThatThrownBy(() -> client.post(ModelEndpoint.RERANK, JSON.readTree("{}")))
                    .isInstanceOf(ModelGatewayException.class)
                    .extracting("kind")
                    .isEqualTo(ModelGatewayException.Kind.TIMEOUT);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(800));
        server.verify(2, postRequestedFor(urlEqualTo("/v1/rerank")));
    }

    @Test
    void normalizesResponseTimeout() throws Exception {
        server.stubFor(post("/v1/embeddings")
                .willReturn(aResponse().withFixedDelay(300).withBody("{}")));

        try (var client = client(Duration.ofMillis(60))) {
            assertThatThrownBy(() -> client.post(ModelEndpoint.EMBEDDINGS, JSON.readTree("{}")))
                    .isInstanceOf(ModelGatewayException.class)
                    .extracting("kind")
                    .isEqualTo(ModelGatewayException.Kind.TIMEOUT);
        }
    }

    @Test
    void normalizesAndRetriesTimeoutWhileReadingResponseBody() throws Exception {
        server.stubFor(post("/v1/embeddings")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}")
                        .withChunkedDribbleDelay(2, 300)));
        var properties = properties(Duration.ofMillis(60), Duration.ofMillis(200), 8_000, 1_000, 100_000);

        try (var client = new OpenAiCompatibleClient(properties, JSON, new ModelUsagePolicy(properties))) {
            assertThatThrownBy(() -> client.post(ModelEndpoint.EMBEDDINGS, JSON.readTree("{}")))
                    .isInstanceOf(ModelGatewayException.class)
                    .extracting("kind")
                    .isEqualTo(ModelGatewayException.Kind.TIMEOUT);
        }
        server.verify(2, postRequestedFor(urlEqualTo("/v1/embeddings")));
    }

    private OpenAiCompatibleClient client(Duration responseTimeout) {
        var properties = properties(
                responseTimeout, responseTimeout.plusMillis(100), 8_000, 1_000, 100_000);
        return new OpenAiCompatibleClient(properties, JSON, new ModelUsagePolicy(properties));
    }

    private ApprovedModelGatewayProperties properties(
            Duration responseTimeout,
            Duration totalTimeout,
            int maxInputTokens,
            int maxOutputTokens,
            long dailyTokenLimit) {
        return new ApprovedModelGatewayProperties(
                true,
                "approved-openai-compatible",
                "model-config-v1",
                URI.create(server.baseUrl()),
                Set.of("localhost"),
                "sk-sensitive-key",
                "embedding-model",
                "reranker-model",
                "chat-model",
                Duration.ofMillis(100),
                responseTimeout,
                totalTimeout,
                maxInputTokens,
                maxOutputTokens,
                2,
                dailyTokenLimit,
                true);
    }
}
