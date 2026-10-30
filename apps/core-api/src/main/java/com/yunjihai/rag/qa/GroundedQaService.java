package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.CitationClaim;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.domain.QaQuestion;
import com.yunjihai.rag.qa.domain.QaStatus;
import com.yunjihai.rag.qa.domain.RefusalReason;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector;
import com.yunjihai.rag.qa.model.ChatCompletionPort;
import com.yunjihai.rag.qa.model.GroundedAnswerPrompt;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.qa.model.ModelCallDescriptor;
import com.yunjihai.rag.qa.model.ModelGeneration;
import com.yunjihai.rag.qa.model.ModelUsage;
import com.yunjihai.rag.qa.sanitization.SanitizationResult;
import com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer;
import com.yunjihai.rag.qa.sanitization.SanitizedText;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.RetrievalOutboundPolicyException;
import com.yunjihai.rag.qa.evaluation.EvaluationCase;
import com.yunjihai.rag.qa.evaluation.EvaluationTraceRegistry;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class GroundedQaService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GroundedQaService.class);
    private static final String POLICY_VERSION = "qa-policy-v1";
    private static final String PROMPT_VERSION = "grounded-answer-v1";
    private static final ModelUsage EMPTY_USAGE = new ModelUsage(0, 0, Duration.ZERO);

    private final AuthorizedSpaceScopePort authorizedSpaces;
    private final RetrievalService retrieval;
    private final QaPersistencePort persistence;
    private final ModelCallAuditPort audits;
    private final QuestionDigestPort digests;
    private final SensitiveContentSanitizer sanitizer;
    private final ChatCompletionPort chat;
    private final EvidenceConflictDetector conflictDetector;
    private final CitationSupportVerifier citationVerifier;
    private final QaPolicy policy;
    private final QaEvidenceAuthorizationPort evidenceAuthorization;
    private final Clock clock;
    private final QaMetrics metrics;
    private final EvaluationTraceRegistry evaluationTraces;

    public GroundedQaService(
            AuthorizedSpaceScopePort authorizedSpaces,
            RetrievalService retrieval,
            QaPersistencePort persistence,
            ModelCallAuditPort audits,
            QuestionDigestPort digests,
            SensitiveContentSanitizer sanitizer,
            ChatCompletionPort chat,
            EvidenceConflictDetector conflictDetector,
            CitationSupportVerifier citationVerifier,
            QaPolicy policy,
            QaEvidenceAuthorizationPort evidenceAuthorization,
            Clock clock) {
        this(authorizedSpaces, retrieval, persistence, audits, digests, sanitizer, chat,
                conflictDetector, citationVerifier, policy, evidenceAuthorization, clock, QaMetrics.noop(),
                new EvaluationTraceRegistry());
    }

    public GroundedQaService(
            AuthorizedSpaceScopePort authorizedSpaces,
            RetrievalService retrieval,
            QaPersistencePort persistence,
            ModelCallAuditPort audits,
            QuestionDigestPort digests,
            SensitiveContentSanitizer sanitizer,
            ChatCompletionPort chat,
            EvidenceConflictDetector conflictDetector,
            CitationSupportVerifier citationVerifier,
            QaPolicy policy,
            QaEvidenceAuthorizationPort evidenceAuthorization,
            Clock clock,
            QaMetrics metrics) {
        this(authorizedSpaces, retrieval, persistence, audits, digests, sanitizer, chat,
                conflictDetector, citationVerifier, policy, evidenceAuthorization, clock, metrics,
                new EvaluationTraceRegistry());
    }

    public GroundedQaService(
            AuthorizedSpaceScopePort authorizedSpaces,
            RetrievalService retrieval,
            QaPersistencePort persistence,
            ModelCallAuditPort audits,
            QuestionDigestPort digests,
            SensitiveContentSanitizer sanitizer,
            ChatCompletionPort chat,
            EvidenceConflictDetector conflictDetector,
            CitationSupportVerifier citationVerifier,
            QaPolicy policy,
            QaEvidenceAuthorizationPort evidenceAuthorization,
            Clock clock,
            QaMetrics metrics,
            EvaluationTraceRegistry evaluationTraces) {
        this.authorizedSpaces = Objects.requireNonNull(authorizedSpaces);
        this.retrieval = Objects.requireNonNull(retrieval);
        this.persistence = Objects.requireNonNull(persistence);
        this.audits = Objects.requireNonNull(audits);
        this.digests = Objects.requireNonNull(digests);
        this.sanitizer = Objects.requireNonNull(sanitizer);
        this.chat = Objects.requireNonNull(chat);
        this.conflictDetector = Objects.requireNonNull(conflictDetector);
        this.citationVerifier = Objects.requireNonNull(citationVerifier);
        this.policy = Objects.requireNonNull(policy);
        this.evidenceAuthorization = Objects.requireNonNull(evidenceAuthorization);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
        this.evaluationTraces = Objects.requireNonNull(evaluationTraces);
    }

    public QaOutcome ask(AskQuestionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        long started = System.nanoTime();
        QaOutcome outcome = execute(command);
        safeMetric(command.requestId(), () -> metrics.recordOutcome(outcome, elapsed(started)));
        LOGGER.info("qa_terminal requestId={} code={}", command.requestId(), terminalCode(outcome));
        return outcome;
    }

    private QaOutcome execute(AskQuestionCommand command) {
        Instant requestTime = clock.instant();
        QaQuestion question;
        try {
            question = QaQuestion.normalize(command.question());
        } catch (RuntimeException invalidQuestion) {
            return new QaOutcome.Failed(command.requestId(), "INVALID_QUESTION");
        }
        if (question.codePointLength() > policy.maxQuestionCodePoints()) {
            return new QaOutcome.Failed(command.requestId(), "INVALID_QUESTION");
        }
        String questionDigest = digests.digest(question.value());
        QaPersistencePort.RequestState state = persistence.create(
                command.requestId(), command.actorId(), questionDigest, requestTime);
        long deadline = System.nanoTime() + policy.totalTimeout().toNanos();

        long authorizationStarted = System.nanoTime();
        Set<UUID> spaces;
        try {
            spaces = Set.copyOf(Objects.requireNonNull(
                    authorizedSpaces.resolve(command.actorId(), requestTime),
                    "authorized spaces must not be null"));
        } catch (RuntimeException unavailable) {
            return complete(state, command.actorId(), new QaOutcome.Failed(
                    command.requestId(), "AUTHORIZED_SCOPE_UNAVAILABLE"));
        } finally {
            safeMetric(command.requestId(), () -> metrics.recordStage(
                    QaMetrics.Stage.AUTHORIZATION, elapsed(authorizationStarted)));
        }
        if (spaces.isEmpty()) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.NO_AUTHORIZED_SPACE, false));
        }
        if (spaces.size() > policy.maxAuthorizedSpaces()) {
            return complete(state, command.actorId(), new QaOutcome.Failed(
                    command.requestId(), "AUTHORIZED_SCOPE_TOO_LARGE"));
        }

        SanitizedText sanitizedQuestion;
        try {
            sanitizedQuestion = Objects.requireNonNull(sanitizer.sanitizeText(question.value()));
        } catch (RuntimeException sanitizationFailure) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.UNSAFE_TO_SEND, false));
        }
        if (sanitizedQuestion.blocked()) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.UNSAFE_TO_SEND, false));
        }
        QaQuestion safeQuestion;
        try {
            safeQuestion = QaQuestion.normalize(sanitizedQuestion.value());
        } catch (RuntimeException invalidSanitizedQuestion) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.UNSAFE_TO_SEND, false));
        }
        if (safeQuestion.codePointLength() > policy.maxQuestionCodePoints()) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.UNSAFE_TO_SEND, false));
        }

        Optional<QaPersistencePort.RequestState> retrieving = persistence.transition(
                command.requestId(), state.revision(), QaStatus.RETRIEVING, clock.instant());
        if (retrieving.isEmpty()) return stale(command);
        state = retrieving.orElseThrow();

        long retrievalStarted = System.nanoTime();
        EvidencePack pack;
        try {
            pack = retrieval.retrieve(new RetrievalQuery(
                    command.requestId(), command.actorId(), spaces, safeQuestion.value(), Map.of(),
                    policy.requestedEvidenceCount()));
        } catch (RetrievalOutboundPolicyException unsafeOutbound) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.UNSAFE_TO_SEND, false));
        } catch (RuntimeException retrievalFailure) {
            return complete(state, command.actorId(), new QaOutcome.Failed(
                    command.requestId(), "RETRIEVAL_FAILED"));
        } finally {
            safeMetric(command.requestId(), () -> metrics.recordStage(
                    QaMetrics.Stage.RETRIEVAL, elapsed(retrievalStarted)));
        }
        if (!command.requestId().equals(pack.requestId())) {
            return complete(state, command.actorId(), new QaOutcome.Failed(
                    command.requestId(), "RETRIEVAL_REQUEST_MISMATCH"));
        }
        evaluationTraces.recordRetrieval(command.requestId(), pack.evidence().stream().limit(10)
                .map(item -> new EvaluationCase.EvidenceRef(item.documentId(), item.nodeId())).toList());
        if (pack.evidence().size() < policy.minEvidenceCount()
                || pack.evidence().stream().mapToDouble(Evidence::score).max().orElse(0.0) <= 0.0) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.INSUFFICIENT_EVIDENCE, pack.degraded()));
        }
        try {
            if (conflictDetector.hasConflict(pack.conflictScreeningEvidence())) {
                return complete(state, command.actorId(), new QaOutcome.Refused(
                        command.requestId(), RefusalReason.CONFLICTING_EVIDENCE, pack.degraded()));
            }
        } catch (RuntimeException detectorFailure) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.CONFLICTING_EVIDENCE, pack.degraded()));
        }

        EvidenceBundle evidence;
        try {
            evidence = EvidenceBundle.from(pack.evidence(), policy);
        } catch (RuntimeException invalidEvidence) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.INSUFFICIENT_EVIDENCE, pack.degraded()));
        }
        Optional<QaPersistencePort.RequestState> saved = persistence.saveEvidence(
                command.requestId(), state.revision(), evidence, pack.degraded(), clock.instant());
        if (saved.isEmpty()) return stale(command);
        state = saved.orElseThrow();

        long sanitizationStarted = System.nanoTime();
        SanitizationResult sanitized;
        try {
            sanitized = sanitizer.sanitize(evidence);
        } catch (RuntimeException sanitizationFailure) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.UNSAFE_TO_SEND, pack.degraded()));
        } finally {
            safeMetric(command.requestId(), () -> metrics.recordStage(
                    QaMetrics.Stage.SANITIZATION, elapsed(sanitizationStarted)));
        }
        int totalSanitizationHits = sanitizationHits(sanitizedQuestion) + sanitizationHits(sanitized);
        safeMetric(command.requestId(), () -> metrics.recordSanitizationHits(totalSanitizationHits));
        if (sanitized.blocked()) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.UNSAFE_TO_SEND, pack.degraded()));
        }
        if (timedOut(deadline)) {
            return complete(state, command.actorId(), new QaOutcome.Failed(
                    command.requestId(), "QA_TIMEOUT", pack.degraded()));
        }

        Optional<QaPersistencePort.RequestState> generating = persistence.transition(
                command.requestId(), state.revision(), QaStatus.GENERATING, clock.instant());
        if (generating.isEmpty()) return stale(command);
        state = generating.orElseThrow();
        GroundedAnswerPrompt prompt = new GroundedAnswerPrompt(
                safeQuestion, sanitized.evidence(), "zh-CN", POLICY_VERSION, PROMPT_VERSION,
                policy.maxModelOutputCodePoints());
        ModelCallDescriptor descriptor = chat.descriptor();
        long generationStarted = System.nanoTime();
        GenerationAttempt attempt;
        try {
            attempt = generate(prompt, command.requestId());
        } finally {
            safeMetric(command.requestId(), () -> metrics.recordStage(
                    QaMetrics.Stage.GENERATION, elapsed(generationStarted)));
        }
        safeMetric(command.requestId(), () -> metrics.recordModelUsage(attempt.usage()));

        try {
            audits.record(new ModelCallAuditPort.ModelCallAudit(
                    command.requestId(), descriptor.provider(), descriptor.endpointIdentifier(),
                    descriptor.model(), descriptor.configVersion(), questionDigest,
                    responseDigest(attempt), attempt.usage(),
                    totalSanitizationHits, attempt.retryCount(), attempt.resultCode(), clock.instant()));
        } catch (RuntimeException auditFailure) {
            return complete(state, command.actorId(), new QaOutcome.Failed(
                    command.requestId(), "MODEL_AUDIT_FAILED", pack.degraded()));
        }

        if (attempt.failure() != null) {
            QaOutcome failureOutcome = mapModelFailure(command.requestId(), attempt.failure(), pack.degraded());
            return complete(state, command.actorId(), failureOutcome);
        }
        long verificationStarted = System.nanoTime();
        try {
            ModelGeneration generation = attempt.generation();
            CandidateAnswer candidate = generation.answer();
            if (candidate.decision() == CandidateAnswer.Decision.REFUSE) {
                return complete(state, command.actorId(), new QaOutcome.Refused(
                        command.requestId(), RefusalReason.INSUFFICIENT_EVIDENCE, pack.degraded()));
            }
            try {
                candidate.validateAgainst(sanitized.evidence());
            } catch (RuntimeException unknownEvidence) {
                return complete(state, command.actorId(), new QaOutcome.Refused(
                        command.requestId(), RefusalReason.INVALID_MODEL_OUTPUT, pack.degraded()));
            }
            var evidenceById = sanitized.evidence().evidence().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            com.yunjihai.rag.qa.domain.QaEvidence::evidenceId,
                            java.util.function.Function.identity()));
            evaluationTraces.recordResponse(command.requestId(), candidate.claims().stream()
                    .flatMap(claim -> claim.supports().stream())
                    .map(support -> evidenceById.get(support.evidenceId()))
                    .filter(Objects::nonNull)
                    .map(item -> new EvaluationCase.EvidenceRef(item.documentId(), item.nodeId()))
                    .toList());

            Optional<QaPersistencePort.RequestState> verifying = persistence.transition(
                    command.requestId(), state.revision(), QaStatus.VERIFYING, clock.instant());
            if (verifying.isEmpty()) return stale(command);
            state = verifying.orElseThrow();
            if (!citationVerifier.isSupported(candidate, sanitized.evidence())) {
                return complete(state, command.actorId(), new QaOutcome.Refused(
                        command.requestId(), RefusalReason.UNSUPPORTED_CLAIM, pack.degraded()));
            }
            if (timedOut(deadline)) {
                return complete(state, command.actorId(), new QaOutcome.Failed(
                        command.requestId(), "QA_TIMEOUT", pack.degraded()));
            }
            var answered = new QaOutcome.Answered(
                    command.requestId(), candidate.answer(), candidate.claims().stream()
                            .map(CitationClaim::from).toList(), pack.degraded());
            try {
                var completed = persistence.completeAuthorized(
                        state.requestId(), state.revision(), answered, clock.instant(),
                        () -> evidenceAuthorization.allAuthorized(
                                command.requestId(), command.actorId(), clock.instant()));
                if (!completed.authorized()) {
                    return complete(state, command.actorId(), new QaOutcome.Refused(
                            command.requestId(), RefusalReason.NO_AUTHORIZED_SPACE, pack.degraded()));
                }
                if (completed.state().isPresent()) return answered;
                return stale(command);
            } catch (RuntimeException authorizationFailure) {
                return complete(state, command.actorId(), new QaOutcome.Failed(
                        command.requestId(), "FINAL_AUTHORIZATION_FAILED", pack.degraded()));
            }
        } finally {
            safeMetric(command.requestId(), () -> metrics.recordStage(
                    QaMetrics.Stage.VERIFICATION, elapsed(verificationStarted)));
        }
    }

    public Optional<QaOutcome> find(UUID requestId, UUID actorId) {
        return persistence.find(
                Objects.requireNonNull(requestId, "requestId must not be null"),
                Objects.requireNonNull(actorId, "actorId must not be null"));
    }

    public Optional<Instant> completedAt(UUID requestId, UUID actorId) {
        return persistence.completedAt(
                Objects.requireNonNull(requestId, "requestId must not be null"),
                Objects.requireNonNull(actorId, "actorId must not be null"));
    }

    private GenerationAttempt generate(GroundedAnswerPrompt prompt, UUID requestId) {
        try {
            ModelGeneration generation = chat.generate(prompt);
            return GenerationAttempt.success(generation, generation.retryCount());
        } catch (ModelGatewayException failure) {
            safeMetric(requestId, () -> metrics.recordModelError(failure.kind()));
            return GenerationAttempt.failure(failure, failure.retryCount());
        } catch (RuntimeException invalidResponse) {
            safeMetric(requestId, () -> metrics.recordModelError(
                    ModelGatewayException.Kind.INVALID_RESPONSE));
            return GenerationAttempt.failure(
                    new ModelGatewayException(ModelGatewayException.Kind.INVALID_RESPONSE), 0);
        }
    }

    private String responseDigest(GenerationAttempt attempt) {
        String value = attempt.failure() == null
                ? attempt.generation().providerRequestId()
                : "MODEL_GATEWAY_" + attempt.failure().kind().name();
        return digests.digest(value);
    }

    private static int sanitizationHits(SanitizationResult result) {
        return result.hitCounts().values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int sanitizationHits(SanitizedText result) {
        return result.hitCounts().values().stream().mapToInt(Integer::intValue).sum();
    }

    private void safeMetric(UUID requestId, Runnable recorder) {
        try {
            recorder.run();
        } catch (RuntimeException metricsFailure) {
            LOGGER.warn("qa_metrics_failed requestId={} code=METRICS_RECORD_FAILED", requestId);
        }
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
    }

    private static String terminalCode(QaOutcome outcome) {
        if (outcome instanceof QaOutcome.Refused refused) {
            return "REFUSED_" + refused.reason().name();
        }
        if (outcome instanceof QaOutcome.Failed failed) {
            return "FAILED_" + failed.code();
        }
        return "ANSWERED";
    }

    private static QaOutcome mapModelFailure(
            UUID requestId, ModelGatewayException failure, boolean degraded) {
        return switch (failure.kind()) {
            case INVALID_RESPONSE -> new QaOutcome.Refused(
                    requestId, RefusalReason.INVALID_MODEL_OUTPUT, degraded);
            case POLICY_VIOLATION -> new QaOutcome.Refused(
                    requestId, RefusalReason.UNSAFE_TO_SEND, degraded);
            case AUTHENTICATION, RATE_LIMITED, TIMEOUT, UNAVAILABLE -> new QaOutcome.Failed(
                    requestId, "MODEL_" + failure.kind().name(), degraded);
        };
    }

    private QaOutcome complete(
            QaPersistencePort.RequestState state, UUID actorId, QaOutcome outcome) {
        if (persistence.complete(
                state.requestId(), state.revision(), outcome, clock.instant()).isPresent()) {
            return outcome;
        }
        return persistence.find(state.requestId(), actorId)
                .orElseGet(() -> new QaOutcome.Failed(state.requestId(), "STALE_QA_RESULT"));
    }

    private QaOutcome stale(AskQuestionCommand command) {
        return persistence.find(command.requestId(), command.actorId())
                .orElseGet(() -> new QaOutcome.Failed(command.requestId(), "STALE_QA_RESULT"));
    }

    private static boolean timedOut(long deadline) {
        return System.nanoTime() - deadline >= 0;
    }

    private record GenerationAttempt(
            ModelGeneration generation,
            ModelGatewayException failure,
            int retryCount) {
        private static GenerationAttempt success(ModelGeneration generation, int retryCount) {
            return new GenerationAttempt(Objects.requireNonNull(generation), null, retryCount);
        }

        private static GenerationAttempt failure(ModelGatewayException failure, int retryCount) {
            return new GenerationAttempt(null, Objects.requireNonNull(failure), retryCount);
        }

        private ModelUsage usage() {
            return generation == null ? EMPTY_USAGE : generation.usage();
        }

        private String resultCode() {
            return failure == null ? "SUCCESS" : "MODEL_" + failure.kind().name();
        }
    }
}
