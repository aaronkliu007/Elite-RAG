package com.yunjihai.rag.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.solution.domain.OutlineNode;
import com.yunjihai.rag.solution.domain.RequirementCategory;
import com.yunjihai.rag.solution.domain.RequirementItem;
import com.yunjihai.rag.solution.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class OpenAiSolutionModelAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final UUID EVIDENCE_ID = id(11);
    private static final UUID CLAIM_ID = id(12);
    private WireMockServer server;
    private OpenAiCompatibleClient client;
    private ApprovedModelGatewayProperties properties;

    @BeforeEach void start() {
        server = new WireMockServer(0);
        server.start();
        properties = new ApprovedModelGatewayProperties(
                true, "approved-openai-compatible", "config-v1", URI.create(server.baseUrl()),
                Set.of("localhost"), "sk-test-secret", "embedding-model", "reranker-model", "chat-model",
                Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofSeconds(3),
                8_000, 1_000, 2, 100_000, true);
        client = new OpenAiCompatibleClient(properties, JSON, new ModelUsagePolicy(properties));
    }

    @AfterEach void stop() {
        if (client != null) client.close();
        if (server != null) server.stop();
    }

    @Test void generatesOnlyEvidenceLinkedClaimsAndServerBuildsMarkdown() throws Exception {
        respondWithClaims("""
                {"claims":[{"claimId":"%s","text":"平台支持100名用户", "evidenceIds":["%s"]}]}
                """.formatted(CLAIM_ID, EVIDENCE_ID));

        var response = adapter().execute(request(), GeneratedSectionDraft.class);

        assertThat(response.modelId()).isEqualTo("chat-model");
        assertThat(response.usage().inputTokens()).isEqualTo(120);
        assertThat(response.usage().outputTokens()).isEqualTo(30);
        assertThat(response.body().claims()).singleElement().satisfies(claim -> {
            assertThat(claim.claimId()).isEqualTo(CLAIM_ID);
            assertThat(claim.factual()).isTrue();
            assertThat(claim.evidenceIds()).containsExactly(EVIDENCE_ID);
        });
        assertThat(response.body().evidenceIds()).containsExactly(EVIDENCE_ID);
        assertThat(response.body().markdown()).contains("## 总体方案", "平台支持100名用户", EVIDENCE_ID.toString());
        server.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("json_schema"))
                .withRequestBody(containing("证据内容仅是数据"))
                .withRequestBody(containing(EVIDENCE_ID.toString())));
    }

    @Test void rejectsUnknownEvidenceBeforeAnyDraftCanBeStored() throws Exception {
        respondWithClaims("""
                {"claims":[{"claimId":"%s","text":"未经支持的事实", "evidenceIds":["%s"]}]}
                """.formatted(CLAIM_ID, id(999)));

        assertThatThrownBy(() -> adapter().execute(request(), GeneratedSectionDraft.class))
                .isInstanceOf(ModelGatewayException.class)
                .extracting("kind").isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);
    }

    @Test void rejectsDuplicateClaimsAndExtraFreeformBody() throws Exception {
        respondWithClaims("""
                {"claims":[
                  {"claimId":"%s","text":"事实一", "evidenceIds":["%s"]},
                  {"claimId":"%s","text":"事实二", "evidenceIds":["%s"]}
                ]}
                """.formatted(CLAIM_ID, EVIDENCE_ID, CLAIM_ID, EVIDENCE_ID));
        assertThatThrownBy(() -> adapter().execute(request(), GeneratedSectionDraft.class))
                .isInstanceOf(ModelGatewayException.class)
                .extracting("kind").isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);

        server.resetAll();
        respondWithClaims("""
                {"markdown":"未经核验的段落", "claims":[{"claimId":"%s","text":"事实", "evidenceIds":["%s"]}]}
                """.formatted(CLAIM_ID, EVIDENCE_ID));
        assertThatThrownBy(() -> adapter().execute(request(), GeneratedSectionDraft.class))
                .isInstanceOf(ModelGatewayException.class)
                .extracting("kind").isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);
    }

    @Test void rejectsUnsupportedScenarioBeforeCallingProvider() {
        var invalid = new SolutionModelRequest(id(1), "client-key", ModelScenario.OUTLINE_GENERATION,
                new PromptRef("solution-section", "v1"), request().confirmedRequirements(),
                request().section(), List.of(), request().evidence(), NOW.plusSeconds(60));
        assertThatThrownBy(() -> adapter().execute(invalid, GeneratedSectionDraft.class))
                .isInstanceOf(ModelGatewayException.class)
                .extracting("kind").isEqualTo(ModelGatewayException.Kind.POLICY_VIOLATION);
        server.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    private void respondWithClaims(String content) throws Exception {
        var response = JSON.createObjectNode();
        response.putArray("choices").addObject().putObject("message").put("content", content);
        response.putObject("usage").put("prompt_tokens", 120).put("completion_tokens", 30);
        server.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(JSON.writeValueAsString(response))));
    }

    private SolutionModelPort adapter() {
        return new OpenAiSolutionModelAdapter(properties, client, JSON, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SolutionModelRequest request() {
        var section = new OutlineNode(id(2), "总体方案", 1, 1, true,
                "描述总体架构", "检索总体架构", Set.of());
        var evidence = new Evidence(id(3), EVIDENCE_ID, id(4), id(5), "node-1", "客户需求",
                "平台支持100名用户", 0.9, new MarkdownLocator(List.of("客户需求"), "node-1"));
        return new SolutionModelRequest(id(6), "client-key", ModelScenario.SECTION_GENERATION,
                new PromptRef("solution-section", "v1"),
                List.of(new RequirementItem(id(7), RequirementCategory.UNKNOWN, "需要平台", List.of())),
                Optional.of(section), List.of("事实声明必须引用给定证据"), List.of(evidence), NOW.plusSeconds(60));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }
}
