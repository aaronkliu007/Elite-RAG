package com.yunjihai.rag.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.QaEvidence;
import com.yunjihai.rag.qa.domain.QaQuestion;
import com.yunjihai.rag.qa.model.GroundedAnswerPrompt;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.retrieval.domain.Candidate;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.ScoredCandidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiAdaptersTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private WireMockServer server;
    private ApprovedModelGatewayProperties properties;
    private OpenAiCompatibleClient client;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(0);
        server.start();
        properties = new ApprovedModelGatewayProperties(
                true, "approved-openai-compatible", "model-config-v1",
                URI.create(server.baseUrl()), Set.of("localhost"), "sk-sensitive-key",
                "embedding-model", "reranker-model", "chat-model",
                Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofSeconds(3),
                8_000, 1_000, 2, 100_000, true);
        client = new OpenAiCompatibleClient(properties, JSON, new ModelUsagePolicy(properties));
    }

    @AfterEach
    void stopServer() {
        if (client != null) client.close();
        if (server != null) server.stop();
    }

    @Test
    void embedsBatchInProviderOrder() {
        server.stubFor(post("/v1/embeddings")
                .withRequestBody(containing("embedding-model"))
                .willReturn(json("""
                        {"data":[
                          {"index":1,"embedding":[0.3,0.4]},
                          {"index":0,"embedding":[0.1,0.2]}
                        ]}
                        """)));

        var result = new OpenAiEmbeddingAdapter(properties, client).embedAll(List.of("one", "two"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).values()).containsExactly(0.1, 0.2);
        assertThat(result.get(1).values()).containsExactly(0.3, 0.4);
    }

    @Test
    void rejectsInconsistentEmbeddingDimensions() {
        server.stubFor(post("/v1/embeddings").willReturn(json("""
                {"data":[
                  {"index":0,"embedding":[0.1,0.2]},
                  {"index":1,"embedding":[0.3]}
                ]}
                """)));

        assertThatThrownBy(() -> new OpenAiEmbeddingAdapter(properties, client).embedAll(List.of("one", "two")))
                .isInstanceOf(ModelGatewayException.class)
                .extracting("kind")
                .isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);
    }

    @Test
    void reranksKnownCandidatesAndRejectsUnknownIds() {
        var first = candidate(UUID.randomUUID(), "first text");
        var second = candidate(UUID.randomUUID(), "second text");
        server.stubFor(post("/v1/rerank").willReturn(json("""
                {"results":[
                  {"candidate_id":"%s","score":0.91},
                  {"candidate_id":"%s","score":0.42}
                ]}
                """.formatted(first.candidate().candidateId(), second.candidate().candidateId()))));

        var scores = new OpenAiRerankerAdapter(properties, client)
                .rerank("safe query", List.of(first, second));

        assertThat(scores).extracting(score -> score.candidateId()).containsExactly(
                first.candidate().candidateId(), second.candidate().candidateId());

        server.resetAll();
        server.stubFor(post("/v1/rerank").willReturn(json("""
                {"results":[{"candidate_id":"%s","score":0.9}]}
                """.formatted(UUID.randomUUID()))));
        assertThatThrownBy(() -> new OpenAiRerankerAdapter(properties, client)
                .rerank("safe query", List.of(first)))
                .isInstanceOf(ModelGatewayException.class)
                .extracting("kind")
                .isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);
    }

    @Test
    void rejectsDuplicateRerankIds() {
        var first = candidate(UUID.randomUUID(), "first text");
        var second = candidate(UUID.randomUUID(), "second text");
        server.stubFor(post("/v1/rerank").willReturn(json("""
                {"results":[
                  {"candidate_id":"%s","score":0.91},
                  {"candidate_id":"%s","score":0.42}
                ]}
                """.formatted(first.candidate().candidateId(), first.candidate().candidateId()))));

        assertThatThrownBy(() -> new OpenAiRerankerAdapter(properties, client)
                .rerank("safe query", List.of(first, second)))
                .isInstanceOf(ModelGatewayException.class)
                .extracting("kind")
                .isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);
    }

    @Test
    void requestsStructuredNonStreamingChatAndBuildsGroundedAnswer() {
        var evidenceId = UUID.randomUUID();
        var claimId = UUID.randomUUID();
        server.stubFor(post("/v1/chat/completions")
                .withRequestBody(containing("json_schema"))
                .withRequestBody(containing("\"stream\":false"))
                .willReturn(json("""
                        {
                          "id":"req-chat-1",
                          "choices":[{"message":{"content":"{\\\"decision\\\":\\\"ANSWER\\\",\\\"answer\\\":\\\"根据证据，制度已生效。\\\",\\\"claims\\\":[{\\\"claimId\\\":\\\"%s\\\",\\\"text\\\":\\\"制度已生效\\\",\\\"supports\\\":[{\\\"evidenceId\\\":\\\"%s\\\",\\\"quote\\\":\\\"制度自今日起生效\\\"}]}]}"}}],
                          "usage":{"prompt_tokens":120,"completion_tokens":30}
                        }
                        """.formatted(claimId, evidenceId))));

        var generation = new OpenAiChatCompletionAdapter(properties, client, JSON)
                .generate(prompt(evidenceId));

        assertThat(generation.answer().answer()).isEqualTo("根据证据，制度已生效。");
        assertThat(generation.answer().claims().getFirst().supports().getFirst().evidenceId())
                .isEqualTo(evidenceId);
        assertThat(generation.usage().totalTokens()).isEqualTo(150);
        assertThat(generation.providerRequestId()).isEqualTo("req-chat-1");
        assertThat(generation.retryCount()).isZero();
        assertThat(new OpenAiChatCompletionAdapter(properties, client, JSON).descriptor())
                .satisfies(descriptor -> {
                    assertThat(descriptor.provider()).isEqualTo("approved-openai-compatible");
                    assertThat(descriptor.endpointIdentifier()).isEqualTo("chat-completions");
                    assertThat(descriptor.model()).isEqualTo("chat-model");
                    assertThat(descriptor.configVersion()).isEqualTo("model-config-v1");
                });
    }

    @Test
    void rejectsChatCitationsOutsideEvidenceBundle() {
        var knownEvidence = UUID.randomUUID();
        server.stubFor(post("/v1/chat/completions").willReturn(json("""
                {
                  "id":"req-chat-2",
                  "choices":[{"message":{"content":"{\\\"decision\\\":\\\"ANSWER\\\",\\\"answer\\\":\\\"unsupported\\\",\\\"claims\\\":[{\\\"claimId\\\":\\\"%s\\\",\\\"text\\\":\\\"unsupported\\\",\\\"supports\\\":[{\\\"evidenceId\\\":\\\"%s\\\",\\\"quote\\\":\\\"unknown\\\"}]}]}"}}],
                  "usage":{"prompt_tokens":10,"completion_tokens":10}
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID()))));

        assertThatThrownBy(() -> new OpenAiChatCompletionAdapter(properties, client, JSON)
                .generate(prompt(knownEvidence)))
                .isInstanceOf(ModelGatewayException.class)
                .extracting("kind")
                .isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);
    }

    @Test
    void rejectsProviderUsageOverConfiguredOutputLimit() {
        var limited = new ApprovedModelGatewayProperties(
                true, "approved-openai-compatible", "model-config-v1",
                URI.create(server.baseUrl()), Set.of("localhost"), "sk-sensitive-key",
                "embedding-model", "reranker-model", "chat-model",
                Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofSeconds(3),
                8_000, 5, 2, 100_000, true);
        server.stubFor(post("/v1/chat/completions").willReturn(json("""
                {
                  "id":"req-over-token",
                  "choices":[{"message":{"content":"{\\\"decision\\\":\\\"REFUSE\\\",\\\"answer\\\":\\\"\\\",\\\"claims\\\":[]}"}}],
                  "usage":{"prompt_tokens":10,"completion_tokens":6}
                }
                """)));

        try (var limitedClient = new OpenAiCompatibleClient(limited, JSON, new ModelUsagePolicy(limited))) {
            assertThatThrownBy(() -> new OpenAiChatCompletionAdapter(limited, limitedClient, JSON)
                    .generate(prompt(UUID.randomUUID())))
                    .isInstanceOf(ModelGatewayException.class)
                    .extracting("kind")
                    .isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);
        }
    }

    private static ScoredCandidate candidate(UUID id, String text) {
        var value = new Candidate(
                UUID.randomUUID(), id, UUID.randomUUID(), UUID.randomUUID(), "node-1", "Title", text,
                new PageLocator(1, null), RecallSource.LEXICAL, 1, 1.0);
        return new ScoredCandidate(value, 1.0, 1, EnumSet.of(RecallSource.LEXICAL));
    }

    private static GroundedAnswerPrompt prompt(UUID evidenceId) {
        var evidence = new QaEvidence(
                evidenceId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "node-1",
                "制度", "制度自今日起生效", 0.9, new PageLocator(1, null));
        return new GroundedAnswerPrompt(
                QaQuestion.normalize("制度何时生效？"),
                new EvidenceBundle(List.of(evidence), evidence.excerpt().length()),
                "zh-CN", "policy-v1", "prompt-v1", 500);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }
}
