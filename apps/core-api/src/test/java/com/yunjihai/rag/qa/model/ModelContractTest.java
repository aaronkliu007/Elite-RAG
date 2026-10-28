package com.yunjihai.rag.qa.model;

import com.yunjihai.rag.qa.QaPolicy;
import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.EvidenceSupport;
import com.yunjihai.rag.qa.domain.GroundedClaim;
import com.yunjihai.rag.qa.domain.QaQuestion;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.embedding.EmbeddingPort;
import com.yunjihai.rag.retrieval.embedding.EmbeddingVector;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelContractTest {

    @Test
    void embedding_port_batches_and_keeps_default_single_item_method() {
        var received = new AtomicReference<List<String>>();
        EmbeddingPort port = texts -> {
            received.set(List.copyOf(texts));
            return texts.stream().map(ignored -> new EmbeddingVector(List.of(0.1, 0.2))).toList();
        };

        assertThat(port.embed("知识检索").values()).containsExactly(0.1, 0.2);
        assertThat(received.get()).containsExactly("知识检索");

        EmbeddingPort broken = texts -> List.of();
        assertThatThrownBy(() -> broken.embed("必须恰好返回一个向量"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prompt_contains_only_minimum_grounded_input() {
        var prompt = new GroundedAnswerPrompt(
                QaQuestion.normalize("如何部署？"), bundle(), "zh-CN",
                "qa-policy-v1", "grounded-answer-v1", 2_000);

        assertThat(prompt.evidence().evidence()).hasSize(8);
        assertThat(GroundedAnswerPrompt.class.getRecordComponents())
                .extracting(component -> component.getName().toLowerCase())
                .noneMatch(name -> name.contains("user")
                        || name.contains("principal")
                        || name.contains("acl")
                        || name.contains("blob")
                        || name.contains("path")
                        || name.contains("endpoint")
                        || name.contains("apikey"));
    }

    @Test
    void model_generation_and_usage_copy_collections_and_reject_negative_metrics() {
        UUID evidenceId = bundle().evidence().getFirst().evidenceId();
        var supports = new ArrayList<>(List.of(new EvidenceSupport(evidenceId, "证据正文")));
        var claims = new ArrayList<>(List.of(new GroundedClaim(id(900), "声明", supports)));
        var candidate = new CandidateAnswer(CandidateAnswer.Decision.ANSWER, "回答", claims);
        var generation = new ModelGeneration(
                candidate,
                new ModelUsage(120, 30, Duration.ofMillis(250)),
                "provider-request-1",
                1);

        supports.clear();
        claims.clear();

        assertThat(generation.answer().claims()).hasSize(1);
        assertThat(generation.usage().totalTokens()).isEqualTo(150);
        assertThat(generation.retryCount()).isEqualTo(1);
        assertThatThrownBy(() -> new ModelUsage(-1, 0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelUsage(0, 0, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void model_gateway_exception_exposes_only_normalized_kind() {
        var failure = new ModelGatewayException(ModelGatewayException.Kind.RATE_LIMITED, 1);

        assertThat(failure.kind()).isEqualTo(ModelGatewayException.Kind.RATE_LIMITED);
        assertThat(failure.retryCount()).isEqualTo(1);
        assertThat(failure.getMessage()).isEqualTo("MODEL_GATEWAY_RATE_LIMITED");
        assertThat(failure.toString()).doesNotContain("key", "question", "evidence");
    }

    private static EvidenceBundle bundle() {
        return EvidenceBundle.from(
                java.util.stream.IntStream.range(0, 8).mapToObj(index -> new Evidence(
                        id(10), id(100 + index), id(200 + index), id(300 + index),
                        "node-" + index, "标题-" + index, "证据正文-" + index, 0.9,
                        new MarkdownLocator(List.of("章节"), "node-" + index))).toList(),
                QaPolicy.defaults());
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
