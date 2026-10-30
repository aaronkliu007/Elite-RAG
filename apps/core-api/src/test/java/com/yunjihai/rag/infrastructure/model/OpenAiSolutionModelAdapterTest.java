package com.yunjihai.rag.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.solution.domain.OutlineNode;
import com.yunjihai.rag.solution.domain.RequirementCategory;
import com.yunjihai.rag.solution.domain.RequirementItem;
import com.yunjihai.rag.solution.domain.RequirementSource;
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

    @Test void proposalTransportFailureDoesNotResendBillablePost() {
        server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(503)));
        var candidateRequest = new SolutionModelRequest(id(21), "candidate-key",
                ModelScenario.REQUIREMENTS_EXTRACTION, new PromptRef("requirements", "v1"),
                List.of(new RequirementItem(id(22), RequirementCategory.UNKNOWN, "待明确需求", List.of())),
                Optional.empty(), List.of("保持来源"), List.of(), NOW.plusSeconds(60));

        assertThatThrownBy(() -> adapter().execute(candidateRequest, GeneratedRequirementProposal.class))
                .isInstanceOf(ModelGatewayException.class);
        server.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
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

    @Test void redactsSensitiveTextFromEverySolutionPromptFieldBeforeProviderCall() throws Exception {
        respondWithClaims("""
                {"claims":[{"claimId":"%s","text":"平台方案", "evidenceIds":["%s"]}]}
                """.formatted(CLAIM_ID, EVIDENCE_ID));
        var section = new OutlineNode(id(2), "联系 alice@example.com", 1, 1, true,
                "咨询 bob@example.com", "检索 carol@example.com", Set.of());
        var evidence = new Evidence(id(3), EVIDENCE_ID, id(4), id(5), "node-1",
                "客户 dave@example.com", "反馈 erin@example.com", 0.9,
                new MarkdownLocator(List.of("客户需求"), "node-1"));
        var sensitive = new SolutionModelRequest(id(6), "client-key", ModelScenario.SECTION_GENERATION,
                new PromptRef("solution-section", "v1"),
                List.of(new RequirementItem(id(7), RequirementCategory.UNKNOWN,
                        "通知 frank@example.com", List.of())), Optional.of(section),
                List.of("规则 grace@example.com"), List.of(evidence), NOW.plusSeconds(60));

        adapter().execute(sensitive, GeneratedSectionDraft.class);

        var requests = server.findAll(postRequestedFor(urlEqualTo("/v1/chat/completions")));
        assertThat(requests).hasSize(1);
        String payload = requests.getFirst().getBodyAsString();
        assertThat(payload).contains("[EMAIL]");
        assertThat(payload).doesNotContain("alice@example.com", "bob@example.com", "carol@example.com",
                "dave@example.com", "erin@example.com", "frank@example.com", "grace@example.com");
    }

    @Test void blocksPrivateKeyInSolutionEvidenceBeforeProviderCall() {
        var evidence = new Evidence(id(3), EVIDENCE_ID, id(4), id(5), "node-1",
                "客户需求", "-----BEGIN PRIVATE KEY-----secret-----END PRIVATE KEY-----", 0.9,
                new MarkdownLocator(List.of("客户需求"), "node-1"));
        var unsafe = new SolutionModelRequest(id(6), "client-key", ModelScenario.SECTION_GENERATION,
                new PromptRef("solution-section", "v1"), request().confirmedRequirements(),
                request().section(), List.of("事实声明必须引用给定证据"),
                List.of(evidence), NOW.plusSeconds(60));

        assertThatThrownBy(() -> adapter().execute(unsafe, GeneratedSectionDraft.class))
                .isInstanceOf(ModelGatewayException.class)
                .extracting("kind").isEqualTo(ModelGatewayException.Kind.POLICY_VIOLATION);
        server.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test void generatesOutlineCandidateFromConfirmedRequirements() throws Exception {
        respondWithClaims("""
                {"nodes":[{"title":"总体架构","level":1,"order":1,"required":true,
                  "objective":"描述部署架构","retrievalHint":"检索架构约束","riskTypes":["PERFORMANCE_OR_SLA"]}]}
                """);
        var outlineRequest = new SolutionModelRequest(id(21), "outline-key", ModelScenario.OUTLINE_GENERATION,
                new PromptRef("outline", "v1"), List.of(new RequirementItem(id(22),
                RequirementCategory.UNKNOWN, "联系 alice@example.com", List.of())),
                Optional.empty(), List.of("至少一个必填章节"), List.of(), NOW.plusSeconds(60));

        var response = adapter().execute(outlineRequest, GeneratedOutlineProposal.class);

        assertThat(response.body().nodes()).singleElement().satisfies(node -> {
            assertThat(node.title()).isEqualTo("总体架构");
            assertThat(node.order()).isEqualTo(1);
            assertThat(node.required()).isTrue();
            assertThat(node.riskTypes()).containsExactly("PERFORMANCE_OR_SLA");
        });
        var body = server.findAll(postRequestedFor(urlEqualTo("/v1/chat/completions")))
                .getFirst().getBodyAsString();
        assertThat(body).contains("[EMAIL]", "outline_proposal").doesNotContain("alice@example.com");
    }

    @Test void rejectsMalformedOutlineCandidate() throws Exception {
        respondWithClaims("""
                {"nodes":[{"title":"总体架构","level":1,"order":2,"required":true,
                  "objective":"部署","retrievalHint":"架构","riskTypes":[]}]}
                """);
        var outlineRequest = new SolutionModelRequest(id(21), "outline-key", ModelScenario.OUTLINE_GENERATION,
                new PromptRef("outline", "v1"), request().confirmedRequirements(), Optional.empty(),
                List.of("至少一个必填章节"), List.of(), NOW.plusSeconds(60));

        assertThatThrownBy(() -> adapter().execute(outlineRequest, GeneratedOutlineProposal.class))
                .isInstanceOf(ModelGatewayException.class)
                .extracting("kind").isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);
    }

    @Test void requirementCandidateMapsOnlyServerSuppliedSourceIndexes() throws Exception {
        respondWithClaims("""
                {"items":[{"category":"GOAL","text":"建设知识库","sourceIndexes":[0]}]}
                """);
        var source = new RequirementSource(new DocumentVersionRef(id(30), id(31)),
                new MarkdownLocator(List.of("客户需求"), "node-1"));
        var requirementRequest = new SolutionModelRequest(id(21), "requirements-key",
                ModelScenario.REQUIREMENTS_EXTRACTION, new PromptRef("requirements", "v1"),
                List.of(new RequirementItem(id(22), RequirementCategory.GOAL,
                        "联系 alice@example.com 建设知识库", List.of(source))),
                Optional.empty(), List.of("已知事实必须包含来源"), List.of(), NOW.plusSeconds(60));

        var response = adapter().execute(requirementRequest, GeneratedRequirementProposal.class);

        assertThat(response.body().items()).singleElement().satisfies(item -> {
            assertThat(item.category()).isEqualTo("GOAL");
            assertThat(item.sources()).containsExactly(source);
        });
        var body = server.findAll(postRequestedFor(urlEqualTo("/v1/chat/completions")))
                .getFirst().getBodyAsString();
        assertThat(body).contains("[EMAIL]", "requirements_proposal", "sourceIndexes")
                .doesNotContain("alice@example.com", id(30).toString(), "node-1");
    }

    @Test void rejectsInventedSourceIndexInRequirementCandidate() throws Exception {
        respondWithClaims("""
                {"items":[{"category":"GOAL","text":"建设知识库","sourceIndexes":[1]}]}
                """);
        var source = new RequirementSource(new DocumentVersionRef(id(30), id(31)),
                new MarkdownLocator(List.of("客户需求"), "node-1"));
        var requirementRequest = new SolutionModelRequest(id(21), "requirements-key",
                ModelScenario.REQUIREMENTS_EXTRACTION, new PromptRef("requirements", "v1"),
                List.of(new RequirementItem(id(22), RequirementCategory.GOAL, "建设知识库", List.of(source))),
                Optional.empty(), List.of(), List.of(), NOW.plusSeconds(60));

        assertThatThrownBy(() -> adapter().execute(requirementRequest, GeneratedRequirementProposal.class))
                .isInstanceOf(ModelGatewayException.class)
                .extracting("kind").isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);
    }

    @Test void extractsDocumentEvidenceUsingOnlyServerAssignedSources() throws Exception {
        respondWithClaims("""
                {"items":[{"category":"GOAL","text":"建设知识库","sourceIndexes":[0]}]}
                """);
        var evidence = new Evidence(id(3), id(11), id(30), id(31), "node-1", "客户需求",
                "联系 alice@example.com 建设知识库", 0.9, new MarkdownLocator(List.of("客户需求"), "node-1"));
        var request = new SolutionModelRequest(id(21), "document-key", ModelScenario.REQUIREMENTS_EXTRACTION,
                new PromptRef("requirements", "v2"), List.of(), Optional.empty(), List.of(), List.of(evidence),
                NOW.plusSeconds(60));
        var result = adapter().execute(request, GeneratedRequirementProposal.class);
        assertThat(result.body().items()).singleElement().satisfies(item -> assertThat(item.sources())
                .containsExactly(new RequirementSource(new DocumentVersionRef(id(30), id(31)), evidence.locator())));
        var body = server.findAll(postRequestedFor(urlEqualTo("/v1/chat/completions"))).getFirst().getBodyAsString();
        assertThat(body).contains("[EMAIL]", "sourceIndex", "requirements_proposal")
                .doesNotContain("alice@example.com", id(30).toString(), id(31).toString(), "node-1");
    }

    @Test void documentExtractionRejectsInventedSourceIndex() throws Exception {
        respondWithClaims("""
                {"items":[{"category":"GOAL","text":"建设知识库","sourceIndexes":[1]}]}
                """);
        var evidence = new Evidence(id(3), id(11), id(30), id(31), "node-1", "客户需求",
                "建设知识库", 0.9, new MarkdownLocator(List.of("客户需求"), "node-1"));
        var request = new SolutionModelRequest(id(21), "document-key", ModelScenario.REQUIREMENTS_EXTRACTION,
                new PromptRef("requirements", "v2"), List.of(), Optional.empty(), List.of(), List.of(evidence),
                NOW.plusSeconds(60));
        assertThatThrownBy(() -> adapter().execute(request, GeneratedRequirementProposal.class))
                .isInstanceOf(ModelGatewayException.class).extracting("kind")
                .isEqualTo(ModelGatewayException.Kind.INVALID_RESPONSE);
    }

    @Test void documentExtractionBlocksSensitiveEvidenceBeforeCallingProvider() {
        var evidence = new Evidence(id(3), id(11), id(30), id(31), "node-1", "客户需求",
                "-----BEGIN PRIVATE KEY-----secret-----END PRIVATE KEY-----", 0.9,
                new MarkdownLocator(List.of("客户需求"), "node-1"));
        var request = new SolutionModelRequest(id(21), "document-key", ModelScenario.REQUIREMENTS_EXTRACTION,
                new PromptRef("requirements", "v2"), List.of(), Optional.empty(), List.of(), List.of(evidence),
                NOW.plusSeconds(60));
        assertThatThrownBy(() -> adapter().execute(request, GeneratedRequirementProposal.class))
                .isInstanceOf(ModelGatewayException.class).extracting("kind")
                .isEqualTo(ModelGatewayException.Kind.POLICY_VIOLATION);
        server.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test void documentExtractionMapsDistinctSourcesAndRejectsMixedInputBeforeExternalCall() throws Exception {
        respondWithClaims("""
                {"items":[{"category":"SCOPE","text":"支持组织协作","sourceIndexes":[1,0]}]}
                """);
        var first = new Evidence(id(3), id(11), id(30), id(31), "node-1", "需求一", "组织协作", 0.9,
                new MarkdownLocator(List.of("范围"), "node-1"));
        var second = new Evidence(id(3), id(12), id(40), id(41), "node-2", "需求二", "跨部门协作", 0.8,
                new MarkdownLocator(List.of("目标"), "node-2"));
        var request = new SolutionModelRequest(id(21), "document-key", ModelScenario.REQUIREMENTS_EXTRACTION,
                new PromptRef("requirements", "v2"), List.of(), Optional.empty(), List.of(), List.of(first, second),
                NOW.plusSeconds(60));
        assertThat(adapter().execute(request, GeneratedRequirementProposal.class).body().items().getFirst().sources())
                .containsExactly(new RequirementSource(new DocumentVersionRef(id(40), id(41)), second.locator()),
                        new RequirementSource(new DocumentVersionRef(id(30), id(31)), first.locator()));
        var mixed = new SolutionModelRequest(id(22), "bad-key", ModelScenario.REQUIREMENTS_EXTRACTION,
                new PromptRef("requirements", "v2"), List.of(new RequirementItem(id(7), RequirementCategory.UNKNOWN,
                        "待明确", List.of())), Optional.empty(), List.of(), List.of(first), NOW.plusSeconds(60));
        assertThatThrownBy(() -> adapter().execute(mixed, GeneratedRequirementProposal.class))
                .isInstanceOf(ModelGatewayException.class).extracting("kind")
                .isEqualTo(ModelGatewayException.Kind.POLICY_VIOLATION);
        server.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test void blocksSensitiveRequirementBeforeAnyProposalProviderCall() {
        var unsafe = new SolutionModelRequest(id(21), "requirements-key",
                ModelScenario.REQUIREMENTS_EXTRACTION, new PromptRef("requirements", "v1"),
                List.of(new RequirementItem(id(22), RequirementCategory.UNKNOWN,
                        "-----BEGIN PRIVATE KEY-----secret-----END PRIVATE KEY-----", List.of())),
                Optional.empty(), List.of(), List.of(), NOW.plusSeconds(60));

        assertThatThrownBy(() -> adapter().execute(unsafe, GeneratedRequirementProposal.class))
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
        return new OpenAiSolutionModelAdapter(properties, client, JSON, Clock.fixed(NOW, ZoneOffset.UTC),
                new RegexSensitiveContentSanitizer(Set.of()));
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
