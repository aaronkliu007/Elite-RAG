package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceSupport;
import com.yunjihai.rag.qa.domain.GroundedClaim;
import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.domain.QaStatus;
import com.yunjihai.rag.qa.domain.RefusalReason;
import com.yunjihai.rag.qa.model.ChatCompletionPort;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.qa.model.ModelGeneration;
import com.yunjihai.rag.qa.model.ModelUsage;
import com.yunjihai.rag.qa.sanitization.SanitizationResult;
import com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer;
import com.yunjihai.rag.qa.sanitization.SanitizedText;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.RetrievalStats;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedQaServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-10T08:00:00Z");

    @Test
    void answersOnlyFromAuthorizedSanitizedEvidenceInTheExpectedStateOrder() {
        var harness = new Harness();
        harness.spaces = Set.of(id(11), id(12));
        harness.pack = pack(evidence(8, 0.9));

        QaOutcome outcome = harness.service().ask(command("  如何　执行制度？ "));

        assertThat(outcome).isInstanceOf(QaOutcome.Answered.class);
        assertThat(((QaOutcome.Answered) outcome).claims().getFirst().evidenceIds())
                .containsExactly(id(100));
        assertThat(harness.queries).singleElement().satisfies(query -> {
            assertThat(query.userId()).isEqualTo(id(2));
            assertThat(query.spaceIds()).containsExactlyInAnyOrder(id(11), id(12));
            assertThat(query.queryText()).isEqualTo("如何 执行制度?");
        });
        assertThat(harness.prompt.get().evidence().evidence())
                .extracting(item -> item.excerpt())
                .allMatch(text -> text.startsWith("证据"));
        assertThat(harness.states).containsExactly(
                QaStatus.RECEIVED, QaStatus.RETRIEVING, QaStatus.SANITIZING,
                QaStatus.GENERATING, QaStatus.VERIFYING, QaStatus.ANSWERED);
        assertThat(harness.audits).hasSize(1);
    }

    @Test
    void refusesBeforeRetrievalWhenNoAuthorizedSpaceExists() {
        var harness = new Harness();
        harness.spaces = Set.of();

        QaOutcome outcome = harness.service().ask(command("问题"));

        assertThat(outcome).isEqualTo(new QaOutcome.Refused(
                id(1), RefusalReason.NO_AUTHORIZED_SPACE, false));
        assertThat(harness.queries).isEmpty();
        assertThat(harness.modelCalls).hasValue(0);
    }

    @Test
    void failsClosedWhenAuthorizedScopeExceedsPolicyLimit() {
        var harness = new Harness();
        harness.spaces = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> id(1000 + index))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        QaOutcome outcome = harness.service().ask(command("问题"));

        assertThat(outcome).isEqualTo(new QaOutcome.Failed(id(1), "AUTHORIZED_SCOPE_TOO_LARGE"));
        assertThat(harness.queries).isEmpty();
    }

    @Test
    void refusesEmptyLowScoreAndConflictingEvidenceWithoutCallingModel() {
        var empty = new Harness();
        empty.pack = pack(List.of());
        assertThat(empty.service().ask(command("问题")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.INSUFFICIENT_EVIDENCE, false));

        var low = new Harness();
        low.pack = pack(evidence(8, 0.0));
        assertThat(low.service().ask(command("问题")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.INSUFFICIENT_EVIDENCE, false));

        var conflict = new Harness();
        var conflicting = new ArrayList<>(evidence(8, 0.9));
        Evidence first = conflicting.getFirst();
        conflicting.set(1, new Evidence(
                first.spaceId(), id(999), first.documentId(), first.versionId(), first.nodeId(),
                first.title(), "冲突正文", first.score(), first.locator()));
        conflict.pack = pack(conflicting);
        assertThat(conflict.service().ask(command("问题")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.CONFLICTING_EVIDENCE, false));
        assertThat(empty.modelCalls.get() + low.modelCalls.get() + conflict.modelCalls.get()).isZero();
    }

    @Test
    void refusesUnsafeEvidenceAndNeverSendsItToChat() {
        var harness = new Harness();
        harness.blockSanitization = true;

        QaOutcome outcome = harness.service().ask(command("问题"));

        assertThat(outcome).isEqualTo(new QaOutcome.Refused(
                id(1), RefusalReason.UNSAFE_TO_SEND, false));
        assertThat(harness.modelCalls).hasValue(0);
    }

    @Test
    void retriesOneTransientModelFailureWithTheSameSanitizedPrompt() {
        var harness = new Harness();
        harness.transientFailures = 1;

        QaOutcome outcome = harness.service().ask(command("问题"));

        assertThat(outcome).isInstanceOf(QaOutcome.Answered.class);
        assertThat(harness.modelCalls).hasValue(2);
        assertThat(harness.audits).singleElement()
                .extracting(ModelCallAuditPort.ModelCallAudit::retryCount)
                .isEqualTo(1);
    }

    @Test
    void rejectsInvalidModelResponseUnknownEvidenceAndForgedQuote() {
        var invalid = new Harness();
        invalid.modelFailure = new ModelGatewayException(ModelGatewayException.Kind.INVALID_RESPONSE);
        assertThat(invalid.service().ask(command("问题")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.INVALID_MODEL_OUTPUT, false));

        var unknown = new Harness();
        unknown.answer = answer(id(999), "证据 正文 0");
        assertThat(unknown.service().ask(command("问题")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.INVALID_MODEL_OUTPUT, false));

        var forged = new Harness();
        forged.answer = answer(id(100), "不存在的引句");
        assertThat(forged.service().ask(command("问题")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.UNSUPPORTED_CLAIM, false));
    }

    @Test
    void auditFailureOrLateCompletionNeverReturnsTheGeneratedAnswer() {
        var auditFailure = new Harness();
        auditFailure.auditFailure = true;
        assertThat(auditFailure.service().ask(command("问题")))
                .isEqualTo(new QaOutcome.Failed(id(1), "MODEL_AUDIT_FAILED"));

        var late = new Harness();
        late.rejectAnsweredCompletion = true;
        late.persistedOutcome = new QaOutcome.Refused(
                id(1), RefusalReason.INSUFFICIENT_EVIDENCE, false);
        assertThat(late.service().ask(command("问题"))).isEqualTo(late.persistedOutcome);
    }

    private static AskQuestionCommand command(String question) {
        return new AskQuestionCommand(id(1), id(2), question);
    }

    private static CandidateAnswer answer(UUID evidenceId, String quote) {
        return new CandidateAnswer(
                CandidateAnswer.Decision.ANSWER, "制度已经生效。",
                List.of(new GroundedClaim(
                        id(800), "制度已经生效",
                        List.of(new EvidenceSupport(evidenceId, quote)))));
    }

    private static List<Evidence> evidence(int count, double score) {
        var result = new ArrayList<Evidence>();
        for (int index = 0; index < count; index++) {
            result.add(new Evidence(
                    id(10), id(100 + index), id(200 + index), id(300 + index),
                    "node-" + index, "制度", "证据　正文 " + index, score,
                    new MarkdownLocator(List.of("第一章"), "node-" + index)));
        }
        return List.copyOf(result);
    }

    private static EvidencePack pack(List<Evidence> evidence) {
        return new EvidencePack(
                id(1), evidence, false, Set.of(),
                new RetrievalStats(evidence.size(), 0, evidence.size(), evidence.size(), evidence.size()), NOW);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }

    private static final class Harness {
        private Set<UUID> spaces = Set.of(id(10));
        private EvidencePack pack = pack(evidence(8, 0.9));
        private CandidateAnswer answer = answer(id(100), "证据 正文 0");
        private int transientFailures;
        private RuntimeException modelFailure;
        private boolean blockSanitization;
        private boolean auditFailure;
        private boolean rejectAnsweredCompletion;
        private QaOutcome persistedOutcome;
        private final List<com.yunjihai.rag.retrieval.domain.RetrievalQuery> queries = new ArrayList<>();
        private final AtomicReference<com.yunjihai.rag.qa.model.GroundedAnswerPrompt> prompt =
                new AtomicReference<>();
        private final AtomicInteger modelCalls = new AtomicInteger();
        private final List<ModelCallAuditPort.ModelCallAudit> audits = new ArrayList<>();
        private final List<QaStatus> states = new ArrayList<>();

        private GroundedQaService service() {
            AuthorizedSpaceScopePort scope = (actor, at) -> spaces;
            RetrievalService retrieval = query -> {
                queries.add(query);
                return pack;
            };
            SensitiveContentSanitizer sanitizer = new SensitiveContentSanitizer() {
                @Override
                public SanitizationResult sanitize(com.yunjihai.rag.qa.domain.EvidenceBundle evidence) {
                    return new SanitizationResult(evidence, Map.of("PHONE", blockSanitization ? 1 : 0),
                            blockSanitization);
                }

                @Override
                public SanitizedText sanitizeText(String text) {
                    return new SanitizedText(text, Map.of(), false);
                }
            };
            ChatCompletionPort chat = value -> {
                modelCalls.incrementAndGet();
                prompt.set(value);
                if (transientFailures-- > 0) {
                    throw new ModelGatewayException(ModelGatewayException.Kind.TIMEOUT);
                }
                if (modelFailure != null) throw modelFailure;
                return new ModelGeneration(
                        answer, new ModelUsage(100, 20, Duration.ofMillis(50)), "provider-1");
            };
            ModelCallAuditPort audit = event -> {
                if (auditFailure) throw new IllegalStateException("audit unavailable");
                audits.add(event);
            };
            return new GroundedQaService(
                    scope, retrieval, new FakePersistence(), audit, value -> "digest:" + value.length(),
                    sanitizer, chat, new ExactQuoteCitationSupportVerifier(), QaPolicy.defaults(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private final class FakePersistence implements QaPersistencePort {
            private RequestState state;

            @Override
            public RequestState create(UUID requestId, UUID actorId, String questionDigest, Instant at) {
                state = new RequestState(requestId, actorId, QaStatus.RECEIVED, 0, false);
                states.add(state.status());
                return state;
            }

            @Override
            public Optional<RequestState> transition(
                    UUID requestId, long expectedRevision, QaStatus next, Instant at) {
                if (state.revision() != expectedRevision || state.status().isTerminal()) return Optional.empty();
                state.status().advanceTo(next);
                state = new RequestState(requestId, state.actorId(), next, state.revision() + 1, state.degraded());
                states.add(next);
                return Optional.of(state);
            }

            @Override
            public Optional<RequestState> saveEvidence(
                    UUID requestId, long expectedRevision,
                    com.yunjihai.rag.qa.domain.EvidenceBundle evidence,
                    boolean degraded, Instant at) {
                if (state.revision() != expectedRevision) return Optional.empty();
                state.status().advanceTo(QaStatus.SANITIZING);
                state = new RequestState(requestId, state.actorId(), QaStatus.SANITIZING,
                        state.revision() + 1, degraded);
                states.add(state.status());
                return Optional.of(state);
            }

            @Override
            public Optional<RequestState> complete(
                    UUID requestId, long expectedRevision, QaOutcome outcome, Instant at) {
                if (rejectAnsweredCompletion && outcome instanceof QaOutcome.Answered) return Optional.empty();
                if (state.revision() != expectedRevision || state.status().isTerminal()) return Optional.empty();
                state.status().advanceTo(outcome.status());
                state = new RequestState(requestId, state.actorId(), outcome.status(),
                        state.revision() + 1, state.degraded());
                states.add(outcome.status());
                persistedOutcome = outcome;
                return Optional.of(state);
            }

            @Override
            public Optional<QaOutcome> find(UUID requestId, UUID actorId) {
                return Optional.ofNullable(persistedOutcome);
            }
        }
    }
}
