package com.yunjihai.rag.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.qa.ExactQuoteCitationSupportVerifier;
import com.yunjihai.rag.qa.domain.*;
import com.yunjihai.rag.qa.model.GroundedAnswerPrompt;
import com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式启用才调用收费接口；所有输入均为虚构数据。 */
@EnabledIfEnvironmentVariable(named = "RAG_DEEPSEEK_LIVE_TEST", matches = "true")
class DeepSeekLiveIntegrationTest {
    private static final UUID EVIDENCE_ID = UUID.fromString("00000000-0000-7000-8000-000000000011");
    private static final String SYNTHETIC_TEXT = "虚构项目星河的建设目标是实现企业知识检索。";

    private static ApprovedModelGatewayProperties properties() {
        return new ApprovedModelGatewayProperties(true, "deepseek", "deepseek-live-test-v1",
                URI.create("https://api.deepseek.com"), Set.of("api.deepseek.com"),
                System.getenv("RAG_AI_API_KEY"), "", "", "deepseek-flash",
                Duration.ofSeconds(5), Duration.ofSeconds(45), Duration.ofSeconds(55),
                8_000, 1_000, 1, 100_000, false);
    }

    @Test
    void answersSyntheticQuestionWithVerifiedExactEvidence() {
        var props = properties();
        try (var client = new OpenAiCompatibleClient(props, new ObjectMapper(), new ModelUsagePolicy(props))) {
            var evidence = new QaEvidence(EVIDENCE_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "node-1", "虚构需求", SYNTHETIC_TEXT, 0.9, new PageLocator(1, null));
            var bundle = new EvidenceBundle(List.of(evidence), SYNTHETIC_TEXT.length());
            var prompt = new GroundedAnswerPrompt(QaQuestion.normalize("虚构项目星河的建设目标是什么？"),
                    bundle, "zh-CN", "policy-v1", "prompt-v1", 600);
            var result = new OpenAiChatCompletionAdapter(props, client, new ObjectMapper(), sanitizer())
                    .generate(prompt);
            assertThat(result.answer().decision()).isEqualTo(CandidateAnswer.Decision.ANSWER);
            assertThat(new ExactQuoteCitationSupportVerifier().isSupported(result.answer(), bundle)).isTrue();
        }
    }

    @Test
    void extractsSyntheticDocumentRequirementsWithAuthorizedSource() {
        var props = properties();
        try (var client = new OpenAiCompatibleClient(props, new ObjectMapper(), new ModelUsagePolicy(props))) {
            var evidence = evidence();
            var request = new SolutionModelRequest(UUID.randomUUID(), "synthetic-live-test",
                    ModelScenario.REQUIREMENTS_EXTRACTION, new PromptRef("requirements", "v2"),
                    List.of(), Optional.empty(), List.of(), List.of(evidence), Instant.now().plusSeconds(60));
            var result = new OpenAiSolutionModelAdapter(props, client, new ObjectMapper(), Clock.systemUTC(), sanitizer())
                    .execute(request, GeneratedRequirementProposal.class);
            assertThat(result.body().items()).isNotEmpty().anySatisfy(item -> {
                assertThat(item.category()).isEqualTo("GOAL");
                assertThat(item.sources()).anySatisfy(source ->
                        assertThat(source.documentVersion().documentId()).isEqualTo(evidence.documentId()));
            });
        }
    }

    @Test
    void generatesSyntheticSectionWithOnlyProvidedEvidence() {
        var props = properties();
        try (var client = new OpenAiCompatibleClient(props, new ObjectMapper(), new ModelUsagePolicy(props))) {
            var section = new OutlineNode(UUID.randomUUID(), "建设目标", 1, 1, true,
                    "描述建设目标", "知识检索", Set.of());
            var requirement = new RequirementItem(UUID.randomUUID(), RequirementCategory.UNKNOWN, "待确认目标", List.of());
            var request = new SolutionModelRequest(UUID.randomUUID(), "synthetic-live-test",
                    ModelScenario.SECTION_GENERATION, new PromptRef("solution-section", "v1"),
                    List.of(requirement), Optional.of(section), List.of("仅引用给定证据"),
                    List.of(evidence()), Instant.now().plusSeconds(60));
            var result = new OpenAiSolutionModelAdapter(props, client, new ObjectMapper(), Clock.systemUTC(), sanitizer())
                    .execute(request, GeneratedSectionDraft.class);
            assertThat(result.body().claims()).isNotEmpty();
            assertThat(result.body().evidenceIds()).containsExactly(EVIDENCE_ID);
        }
    }

    private static Evidence evidence() {
        return new Evidence(UUID.randomUUID(), EVIDENCE_ID, UUID.randomUUID(), UUID.randomUUID(),
                "node-1", "虚构需求", SYNTHETIC_TEXT, 0.9, new MarkdownLocator(List.of("建设目标"), "node-1"));
    }

    private static RegexSensitiveContentSanitizer sanitizer() {
        return new RegexSensitiveContentSanitizer(Set.of());
    }
}
