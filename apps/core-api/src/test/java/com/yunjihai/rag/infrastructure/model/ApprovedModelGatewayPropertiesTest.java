package com.yunjihai.rag.infrastructure.model;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.List;
import com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer;
import com.yunjihai.rag.retrieval.embedding.EmbeddingUnavailableException;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovedModelGatewayPropertiesTest {

    @Test
    void chatOnlyGatewayDoesNotCallUnavailableEmbeddingOrReranker() {
        var server = new WireMockServer(0);
        server.start();
        try {
            var properties = new ApprovedModelGatewayProperties(true, "deepseek", "config-v1",
                    URI.create(server.baseUrl()), Set.of("localhost"), "test-key",
                    "", "", "deepseek-flash", Duration.ofSeconds(1), Duration.ofSeconds(2),
                    Duration.ofSeconds(3), 8000, 1000, 1, 10000, true);
            try (var client = new OpenAiCompatibleClient(properties,new ObjectMapper(),new ModelUsagePolicy(properties))) {
                var sanitizer = new RegexSensitiveContentSanitizer(Set.of());
                var config = new ModelGatewayConfiguration();
                var embedding = config.approvedEmbeddingPort(properties,client,sanitizer,false);
                var reranker = config.approvedRerankerPort(properties,client,sanitizer);

                assertThatThrownBy(() -> embedding.embedAll(List.of("虚构测试")))
                        .isInstanceOf(EmbeddingUnavailableException.class);
                assertThatThrownBy(() -> reranker.rerank("虚构测试",List.of()))
                        .isInstanceOf(ModelGatewayException.class);
                assertThat(server.getAllServeEvents()).isEmpty();
                assertThatThrownBy(() -> config.approvedEmbeddingPort(properties,client,sanitizer,true))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("MODEL_GATEWAY_CONFIGURATION_INVALID");
            }
        } finally { server.stop(); }
    }

    @Test
    void acceptsApprovedHttpsEndpointAndRedactsCredential() {
        var properties = enabled(URI.create("https://models.example.com"), Set.of("models.example.com"), false);

        assertThat(properties.provider()).isEqualTo("approved-openai-compatible");
        assertThat(properties.configVersion()).isEqualTo("model-config-v1");
        assertThat(properties.endpointUri("/v1/embeddings"))
                .isEqualTo(URI.create("https://models.example.com/v1/embeddings"));
        assertThat(properties.toString())
                .contains("<redacted>")
                .doesNotContain("sk-sensitive-key");
    }

    @Test
    void rejectsUnapprovedOrInsecureProductionEndpoints() {
        assertThatThrownBy(() -> enabled(
                URI.create("https://unapproved.example.com"), Set.of("models.example.com"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_GATEWAY_CONFIGURATION_INVALID");

        assertThatThrownBy(() -> enabled(
                URI.create("http://models.example.com"), Set.of("models.example.com"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_GATEWAY_CONFIGURATION_INVALID");
    }

    @Test
    void rejectsAuditIdentifiersThatCouldContainUrlsOrFreeText() {
        assertThatThrownBy(() -> new ApprovedModelGatewayProperties(
                true, "https://models.example.com", "model config v1",
                URI.create("https://models.example.com"), Set.of("models.example.com"),
                "sk-sensitive-key", "embedding-model", "reranker-model", "chat-model",
                Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(3),
                8_000, 1_000, 4, 100_000, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_GATEWAY_CONFIGURATION_INVALID");
    }

    @Test
    void permitsLoopbackHttpOnlyBehindExplicitTestSwitch() {
        assertThatThrownBy(() -> enabled(
                URI.create("http://127.0.0.1:8080"), Set.of("127.0.0.1"), false))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(enabled(URI.create("http://127.0.0.1:8080"), Set.of("127.0.0.1"), true).enabled())
                .isTrue();

        assertThatThrownBy(() -> enabled(
                URI.create("http://127.attacker.example:8080"), Set.of("127.attacker.example"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MODEL_GATEWAY_CONFIGURATION_INVALID");
    }

    @Test
    void enforcesConcurrencyAndDailyDeploymentBudget() {
        var limited = new ApprovedModelGatewayProperties(
                true, "approved-openai-compatible", "model-config-v1",
                URI.create("https://models.example.com"), Set.of("models.example.com"),
                "sk-sensitive-key", "embedding-model", "reranker-model", "chat-model",
                Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(3),
                1, 1, 1, 2, false);
        var policy = new ModelUsagePolicy(limited);

        try (var first = policy.acquire(1)) {
            assertThatThrownBy(() -> policy.acquire(1))
                    .isInstanceOf(com.yunjihai.rag.qa.model.ModelGatewayException.class)
                    .extracting("kind")
                    .isEqualTo(com.yunjihai.rag.qa.model.ModelGatewayException.Kind.RATE_LIMITED);
        }
        try (var second = policy.acquire(1)) {
            assertThat(second).isNotNull();
        }
        assertThatThrownBy(() -> policy.acquire(1))
                .isInstanceOf(com.yunjihai.rag.qa.model.ModelGatewayException.class)
                .extracting("kind")
                .isEqualTo(com.yunjihai.rag.qa.model.ModelGatewayException.Kind.RATE_LIMITED);
    }

    @Test
    void doesNotRefundNewDayUsageWhenPreviousDayCallCompletesAfterMidnight() {
        var limited = new ApprovedModelGatewayProperties(
                true, "approved-openai-compatible", "model-config-v1",
                URI.create("https://models.example.com"), Set.of("models.example.com"),
                "sk-sensitive-key", "embedding-model", "reranker-model", "chat-model",
                Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofSeconds(3),
                1, 1, 3, 2, false);
        var clock = new MutableClock(Instant.parse("2026-10-10T23:59:59Z"));
        var policy = new ModelUsagePolicy(limited, clock);

        var previousDay = policy.acquire(1);
        clock.set(Instant.parse("2026-10-11T00:00:01Z"));
        try (var firstToday = policy.acquire(1)) {
            previousDay.complete(0);
            previousDay.close();
        }
        try (var secondToday = policy.acquire(1)) {
            assertThat(secondToday).isNotNull();
        }
        assertThatThrownBy(() -> policy.acquire(1))
                .isInstanceOf(com.yunjihai.rag.qa.model.ModelGatewayException.class)
                .extracting("kind")
                .isEqualTo(com.yunjihai.rag.qa.model.ModelGatewayException.Kind.RATE_LIMITED);
    }

    static ApprovedModelGatewayProperties enabled(URI endpoint, Set<String> hosts, boolean loopbackHttp) {
        return new ApprovedModelGatewayProperties(
                true,
                "approved-openai-compatible",
                "model-config-v1",
                endpoint,
                hosts,
                "sk-sensitive-key",
                "embedding-model",
                "reranker-model",
                "chat-model",
                Duration.ofMillis(250),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                8_000,
                1_000,
                4,
                100_000,
                loopbackHttp);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("test clock is UTC only");
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
