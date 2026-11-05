package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceSupport;
import com.yunjihai.rag.qa.domain.GroundedClaim;
import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.domain.QaStatus;
import com.yunjihai.rag.qa.domain.RefusalReason;
import com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector;
import com.yunjihai.rag.qa.model.ChatCompletionPort;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.qa.model.ModelCallDescriptor;
import com.yunjihai.rag.qa.model.ModelGeneration;
import com.yunjihai.rag.qa.model.ModelUsage;
import com.yunjihai.rag.qa.sanitization.SanitizationResult;
import com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer;
import com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer;
import com.yunjihai.rag.qa.sanitization.SanitizedText;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.RetrievalStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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

@ExtendWith(OutputCaptureExtension.class)
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
        assertThat(harness.audits.getFirst()).satisfies(audit -> {
            assertThat(audit.provider()).isEqualTo("test-provider");
            assertThat(audit.endpointIdentifier()).isEqualTo("chat-completions");
            assertThat(audit.model()).isEqualTo("test-chat-model");
            assertThat(audit.modelConfigVersion()).isEqualTo("test-config-v1");
        });
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
    void refusesCrossDocumentSemanticConflictBeforeCallingModel() {
        var harness = new Harness();
        var conflicting = new ArrayList<>(evidence(8, 0.9));
        Evidence first = conflicting.getFirst();
        Evidence second = conflicting.get(1);
        conflicting.set(0, new Evidence(
                first.spaceId(), first.evidenceId(), first.documentId(), first.versionId(), first.nodeId(),
                first.title(), "项目预算：1万元", first.score(), first.locator()));
        conflicting.set(1, new Evidence(
                second.spaceId(), second.evidenceId(), second.documentId(), second.versionId(), second.nodeId(),
                second.title(), "项目预算为2万元", second.score(), second.locator()));
        harness.pack = pack(conflicting);

        QaOutcome outcome = harness.service().ask(command("项目预算是多少？"));

        assertThat(outcome).isEqualTo(new QaOutcome.Refused(
                id(1), RefusalReason.CONFLICTING_EVIDENCE, false));
        assertThat(harness.modelCalls).hasValue(0);
    }

    @Test
    void refusesConflictInAuthorizedCandidatesBeyondSelectedEvidence() {
        var harness = new Harness();
        var selected = evidence(8, 0.9);
        var screening = new ArrayList<>(selected);
        var locator = new MarkdownLocator(List.of("审批"), "node-extra");
        screening.add(new Evidence(id(10), id(900), id(901), id(902), "node-extra-1",
                "审批一", "该方案已经批准。", 0.1, locator));
        screening.add(new Evidence(id(10), id(903), id(904), id(905), "node-extra-2",
                "审批二", "该方案未批准。", 0.1, locator));
        harness.pack = new EvidencePack(id(1), selected, false, Set.of(),
                new RetrievalStats(10, 0, 10, 10, 8), NOW, screening);

        assertThat(harness.service().ask(command("方案是否批准？")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.CONFLICTING_EVIDENCE, false));
        assertThat(harness.modelCalls).hasValue(0);
    }

    @Test
    void neverSendsExtraScreeningCandidatesToModel() {
        var harness = new Harness();
        var selected = evidence(8, 0.9);
        var screening = new ArrayList<>(selected);
        screening.add(new Evidence(id(10), id(950), id(951), id(952), "extra",
                "额外候选", "只用于内部冲突筛查", 0.1,
                new MarkdownLocator(List.of("其他"), "extra")));
        harness.pack = new EvidencePack(id(1), selected, false, Set.of(),
                new RetrievalStats(9, 0, 9, 9, 8), NOW, screening);

        assertThat(harness.service().ask(command("问题"))).isInstanceOf(QaOutcome.Answered.class);
        assertThat(harness.prompt.get().evidence().evidence()).hasSize(8)
                .extracting(item -> item.title()).doesNotContain("额外候选");
    }

    @Test
    void recordsAuthorizedRetrievalIdentitiesForEvaluationBeforeConflictRefusal() throws Exception {
        var harness = new Harness();
        harness.traces.begin(id(1));
        harness.conflictDetector = ignored -> true;
        assertThat(harness.service().ask(command("问题")))
                .isInstanceOf(QaOutcome.Refused.class);
        var trace = harness.traces.consume(id(1));
        assertThat(trace.retrievalObserved()).isTrue();
        assertThat(trace.conflictScreeningCompleted()).isTrue();
        assertThat(trace.conflictScreeningUnavailable()).isFalse();
        assertThat(trace.retrievedTop10()).hasSize(8);
        assertThat(trace.sanitizedQuestionDigest()).isEqualTo(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest("问题".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThat(harness.modelCalls).hasValue(0);
    }

    @Test
    void failsClosedWhenConflictDetectorIsUnavailable() {
        var harness = new Harness();
        harness.conflictDetector = ignored -> {
            throw new IllegalStateException("detector unavailable");
        };

        QaOutcome outcome = harness.service().ask(command("问题"));

        assertThat(outcome).isEqualTo(new QaOutcome.Refused(
                id(1), RefusalReason.CONFLICTING_EVIDENCE, false));
        assertThat(harness.modelCalls).hasValue(0);
    }

    @Test
    void multilineNumericMergedTablesRefuseBeforeModelAndRecordScreeningFailure() {
        var harness=new Harness();
        var items=new ArrayList<>(evidence(8,0.9));
        for (int i=0; i<2; i++) {
            var item=items.get(i);
            items.set(i,new Evidence(item.spaceId(),item.evidenceId(),item.documentId(),item.versionId(),
                    item.nodeId(),item.title(),item.excerpt(),item.score(),
                    new com.yunjihai.rag.retrieval.domain.TableLocator("预算",1,2,1,2)));
        }
        harness.pack=pack(items);
        harness.conflictDetector=new DeterministicEvidenceConflictDetector(item -> List.of(
                new com.yunjihai.rag.ingestion.CanonicalTableCell(0,0,1,2,"预算\n100万元\n")));
        harness.traces.begin(id(1));

        assertThat(harness.service().ask(command("预算是多少？")))
                .isEqualTo(new QaOutcome.Refused(id(1),RefusalReason.CONFLICTING_EVIDENCE,false));
        assertThat(harness.modelCalls).hasValue(0);
        var trace=harness.traces.consume(id(1));
        assertThat(trace.conflictScreeningUnavailable()).isTrue();
        assertThat(trace.conflictScreeningCompleted()).isFalse();
    }
    @Test
    void conflictDetectionLogsOnlyCategoryAndRequestId(CapturedOutput output) {
        var harness = new Harness();
        harness.conflictDetector = ignored -> true;
        assertThat(harness.service().ask(command("私人问题")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.CONFLICTING_EVIDENCE, false));
        assertThat(harness.modelCalls).hasValue(0);
        assertThat(output).contains("qa_conflict_screening_detected", "requestId=" + id(1), "kind=OTHER");
        assertThat(output).doesNotContain("私人问题");
    }

    @Test
    void conflictDetectorFailureLogsOnlyASafeDiagnosticWithoutChangingStrictRefusal(CapturedOutput output) {
        var harness = new Harness();
        harness.conflictDetector = ignored -> { throw new IllegalStateException("PRIVATE_DOCUMENT_CONTENT"); };
        harness.traces.begin(id(1));
        assertThat(harness.service().ask(command("私人问题")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.CONFLICTING_EVIDENCE, false));
        assertThat(harness.modelCalls).hasValue(0);
        assertThat(output).contains("qa_conflict_screening_unavailable", "requestId=" + id(1));
        assertThat(output).doesNotContain("PRIVATE_DOCUMENT_CONTENT", "私人问题");
        var trace=harness.traces.consume(id(1));
        assertThat(trace.conflictScreeningUnavailable()).isTrue();
        assertThat(trace.conflictScreeningCompleted()).isFalse();
    }

    @Test
    void refusesWhenConflictScreeningExceedsFactLimit() {
        var harness = new Harness();
        var selected = evidence(8, 0.9);
        var screening = new ArrayList<>(selected);
        for (int index = 0; index < 30; index++) {
            int item = index;
            String text = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(fact -> "指标" + item + "项" + fact + "为1天；")
                    .collect(java.util.stream.Collectors.joining());
            screening.add(new Evidence(id(10), id(1000 + index), id(1100 + index), id(1200 + index),
                    "dense-" + index, "密集资料", text, 0.1,
                    new MarkdownLocator(List.of("章节"), "dense-" + index)));
        }
        harness.pack = new EvidencePack(id(1), selected, false, Set.of(),
                new RetrievalStats(38, 0, 38, 38, 8), NOW, screening);

        assertThat(harness.service().ask(command("问题")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.CONFLICTING_EVIDENCE, false));
        assertThat(harness.modelCalls).hasValue(0);
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
    void redactsQuestionBeforeRetrievalAndChatModel() throws Exception {
        var harness = new Harness();
        var metrics = new RecordingQaMetrics();
        harness.metrics = metrics;
        harness.traces.begin(id(1));

        QaOutcome outcome = harness.service().ask(command("请联系 alice@example.com 查询制度"));

        assertThat(outcome).isInstanceOf(QaOutcome.Answered.class);
        assertThat(harness.queries).singleElement().satisfies(query ->
                assertThat(query.queryText()).isEqualTo("请联系 [EMAIL] 查询制度"));
        assertThat(harness.prompt.get().question().value())
                .isEqualTo("请联系 [EMAIL] 查询制度");
        assertThat(harness.modelCalls).hasValue(1);
        assertThat(metrics.sanitizationHits).containsExactly(1);
        assertThat(harness.audits).singleElement()
                .extracting(ModelCallAuditPort.ModelCallAudit::sanitizationHitCount).isEqualTo(1);
        var trace=harness.traces.consume(id(1));
        assertThat(trace.sanitizedQuestionDigest()).isEqualTo(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                        "请联系 [EMAIL] 查询制度".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void blocksUnsafeQuestionBeforeRetrievalOrModelCall() {
        var harness = new Harness();

        QaOutcome outcome = harness.service().ask(command(
                "请处理 -----BEGIN PRIVATE KEY-----secret-----END PRIVATE KEY-----"));

        assertThat(outcome).isEqualTo(new QaOutcome.Refused(
                id(1), RefusalReason.UNSAFE_TO_SEND, false));
        assertThat(harness.queries).isEmpty();
        assertThat(harness.modelCalls).hasValue(0);
    }

    @Test
    void refusesWhenQuestionSanitizerReturnsNoResult() {
        var harness = new Harness();
        harness.nullQuestionSanitization = true;

        QaOutcome outcome = harness.service().ask(command("问题"));

        assertThat(outcome).isEqualTo(new QaOutcome.Refused(
                id(1), RefusalReason.UNSAFE_TO_SEND, false));
        assertThat(harness.queries).isEmpty();
        assertThat(harness.modelCalls).hasValue(0);
    }

    @Test
    void delegatesTheSingleRetryBudgetToTheHttpGateway() {
        var harness = new Harness();
        harness.transientFailures = 1;
        var metrics = new RecordingQaMetrics();
        harness.metrics = metrics;

        QaOutcome outcome = harness.service().ask(command("问题"));

        assertThat(outcome).isEqualTo(new QaOutcome.Failed(id(1), "MODEL_TIMEOUT", false));
        assertThat(harness.modelCalls).hasValue(1);
        assertThat(harness.audits).singleElement()
                .extracting(ModelCallAuditPort.ModelCallAudit::retryCount)
                .isEqualTo(1);
        assertThat(metrics.modelErrors).containsExactly(ModelGatewayException.Kind.TIMEOUT);
    }

    @Test
    void recordsEveryAnsweredPipelineStageAndSafeOperationalTotals() {
        var harness = new Harness();
        var metrics = new RecordingQaMetrics();
        harness.metrics = metrics;

        QaOutcome outcome = harness.service().ask(command("问题"));

        assertThat(outcome).isInstanceOf(QaOutcome.Answered.class);
        assertThat(metrics.stages).containsExactly(
                QaMetrics.Stage.AUTHORIZATION,
                QaMetrics.Stage.RETRIEVAL,
                QaMetrics.Stage.SANITIZATION,
                QaMetrics.Stage.GENERATION,
                QaMetrics.Stage.VERIFICATION);
        assertThat(metrics.outcomes).containsExactly(QaStatus.ANSWERED);
        assertThat(metrics.modelUsages).singleElement().satisfies(usage -> {
            assertThat(usage.inputTokens()).isEqualTo(100);
            assertThat(usage.outputTokens()).isEqualTo(20);
        });
        assertThat(metrics.sanitizationHits).containsExactly(0);
    }

    @Test
    void metricsFailureCannotChangeOutcomeOrLeakPayloadsToLogs(CapturedOutput output) {
        var harness = new Harness();
        harness.metrics = new ThrowingQaMetrics("api-key-secret");

        QaOutcome outcome = harness.service().ask(command("question-secret"));

        assertThat(outcome).isInstanceOf(QaOutcome.Answered.class);
        assertThat(output).contains(id(1).toString(), "METRICS_RECORD_FAILED")
                .doesNotContain("question-secret")
                .doesNotContain("证据　正文")
                .doesNotContain("制度已经生效")
                .doesNotContain("api-key-secret");
    }

    @Test
    void refusesAnswerThatQuotesOnlyThePositivePartOfANegatedFact() {
        var harness = new Harness();
        var values = new ArrayList<>(evidence(8, 0.9));
        var first = values.getFirst();
        values.set(0, new Evidence(first.spaceId(), first.evidenceId(), first.documentId(), first.versionId(),
                first.nodeId(), first.title(), "该方案未批准。", first.score(), first.locator()));
        harness.pack = pack(values);
        harness.answer = answer(first.evidenceId(), "批准");

        assertThat(harness.service().ask(command("方案是否批准？")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.UNSUPPORTED_CLAIM, false));
        assertThat(harness.modelCalls).hasValue(1);
    }

    @Test
    void refusesAnswerThatDropsFollowingBudgetRangeQualifier() {
        var harness = new Harness();
        var values = new ArrayList<>(evidence(8, 0.9));
        var first = values.getFirst();
        values.set(0, new Evidence(first.spaceId(), first.evidenceId(), first.documentId(), first.versionId(),
                first.nodeId(), first.title(), "该项目预算为100万元以上。", first.score(), first.locator()));
        harness.pack = pack(values);
        harness.answer = answer(first.evidenceId(), "100万元");

        assertThat(harness.service().ask(command("项目预算是多少？")))
                .isEqualTo(new QaOutcome.Refused(id(1), RefusalReason.UNSUPPORTED_CLAIM, false));
        assertThat(harness.modelCalls).hasValue(1);
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

    @Test
    void refusesLateModelResultWhenEvidenceAuthorizationWasRevoked() {
        var harness = new Harness();
        harness.finalAuthorization = false;

        QaOutcome outcome = harness.service().ask(command("问题"));

        assertThat(outcome).isEqualTo(new QaOutcome.Refused(
                id(1), RefusalReason.NO_AUTHORIZED_SPACE, false));
        assertThat(harness.modelCalls).hasValue(1);
        assertThat(harness.states).containsExactly(
                QaStatus.RECEIVED, QaStatus.RETRIEVING, QaStatus.SANITIZING,
                QaStatus.GENERATING, QaStatus.VERIFYING, QaStatus.REFUSED);
    }

    private static AskQuestionCommand command(String question) {
        return new AskQuestionCommand(id(1), id(2), question);
    }

    private static CandidateAnswer answer(UUID evidenceId, String quote) {
        return new CandidateAnswer(
                CandidateAnswer.Decision.ANSWER, quote,
                List.of(new GroundedClaim(
                        id(800), quote,
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
        private boolean nullQuestionSanitization;
        private boolean auditFailure;
        private boolean rejectAnsweredCompletion;
        private boolean finalAuthorization = true;
        private QaOutcome persistedOutcome;
        private QaMetrics metrics = QaMetrics.noop();
        private final com.yunjihai.rag.qa.evaluation.EvaluationTraceRegistry traces =
                new com.yunjihai.rag.qa.evaluation.EvaluationTraceRegistry();
        private EvidenceConflictDetector conflictDetector = new DeterministicEvidenceConflictDetector();
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
                private final RegexSensitiveContentSanitizer real =
                        new RegexSensitiveContentSanitizer(Set.of());
                @Override
                public SanitizationResult sanitize(com.yunjihai.rag.qa.domain.EvidenceBundle evidence) {
                    return new SanitizationResult(evidence, Map.of("PHONE", blockSanitization ? 1 : 0),
                            blockSanitization);
                }

                @Override
                public SanitizedText sanitizeText(String text) {
                    if (nullQuestionSanitization) return null;
                    return real.sanitizeText(text);
                }
            };
            ChatCompletionPort chat = new ChatCompletionPort() {
                @Override
                public ModelCallDescriptor descriptor() {
                    return new ModelCallDescriptor(
                            "test-provider", "chat-completions", "test-chat-model", "test-config-v1");
                }

                @Override
                public ModelGeneration generate(com.yunjihai.rag.qa.model.GroundedAnswerPrompt value) {
                    modelCalls.incrementAndGet();
                    prompt.set(value);
                    if (transientFailures-- > 0) {
                        throw new ModelGatewayException(ModelGatewayException.Kind.TIMEOUT, 1);
                    }
                    if (modelFailure != null) throw modelFailure;
                    return new ModelGeneration(
                            answer, new ModelUsage(100, 20, Duration.ofMillis(50)), "provider-1", 0);
                }
            };
            ModelCallAuditPort audit = event -> {
                if (auditFailure) throw new IllegalStateException("audit unavailable");
                audits.add(event);
            };
            return new GroundedQaService(
                    scope, retrieval, new FakePersistence(), audit, value -> "digest:" + value.length(),
                    sanitizer, chat, conflictDetector, new ExactQuoteCitationSupportVerifier(), QaPolicy.defaults(),
                    (requestId, actorId, at) -> finalAuthorization,
                    Clock.fixed(NOW, ZoneOffset.UTC), metrics, traces);
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

    private static final class RecordingQaMetrics implements QaMetrics {
        private final List<Stage> stages = new ArrayList<>();
        private final List<QaStatus> outcomes = new ArrayList<>();
        private final List<ModelUsage> modelUsages = new ArrayList<>();
        private final List<Integer> sanitizationHits = new ArrayList<>();
        private final List<ModelGatewayException.Kind> modelErrors = new ArrayList<>();

        @Override
        public void recordStage(Stage stage, Duration duration) {
            stages.add(stage);
        }

        @Override
        public void recordOutcome(QaOutcome outcome, Duration duration) {
            outcomes.add(outcome.status());
        }

        @Override
        public void recordModelUsage(ModelUsage usage) {
            modelUsages.add(usage);
        }

        @Override
        public void recordSanitizationHits(int count) {
            sanitizationHits.add(count);
        }

        @Override
        public void recordModelError(ModelGatewayException.Kind kind) {
            modelErrors.add(kind);
        }
    }

    private static final class ThrowingQaMetrics implements QaMetrics {
        private final String secret;

        private ThrowingQaMetrics(String secret) {
            this.secret = secret;
        }

        @Override public void recordStage(Stage stage, Duration duration) { fail(); }
        @Override public void recordOutcome(QaOutcome outcome, Duration duration) { fail(); }
        @Override public void recordModelUsage(ModelUsage usage) { fail(); }
        @Override public void recordSanitizationHits(int count) { fail(); }
        @Override public void recordModelError(ModelGatewayException.Kind kind) { fail(); }

        private void fail() {
            throw new IllegalStateException(secret);
        }
    }
}
