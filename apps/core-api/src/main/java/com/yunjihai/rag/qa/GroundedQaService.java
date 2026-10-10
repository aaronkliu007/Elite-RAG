package com.yunjihai.rag.qa;

import com.yunjihai.rag.qa.domain.CandidateAnswer;
import com.yunjihai.rag.qa.domain.EvidenceBundle;
import com.yunjihai.rag.qa.domain.QaOutcome;
import com.yunjihai.rag.qa.domain.QaQuestion;
import com.yunjihai.rag.qa.domain.QaStatus;
import com.yunjihai.rag.qa.domain.RefusalReason;
import com.yunjihai.rag.qa.model.ChatCompletionPort;
import com.yunjihai.rag.qa.model.GroundedAnswerPrompt;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.qa.model.ModelGeneration;
import com.yunjihai.rag.qa.model.ModelUsage;
import com.yunjihai.rag.qa.sanitization.SanitizationResult;
import com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class GroundedQaService {
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
    private final CitationSupportVerifier citationVerifier;
    private final QaPolicy policy;
    private final Clock clock;

    public GroundedQaService(
            AuthorizedSpaceScopePort authorizedSpaces,
            RetrievalService retrieval,
            QaPersistencePort persistence,
            ModelCallAuditPort audits,
            QuestionDigestPort digests,
            SensitiveContentSanitizer sanitizer,
            ChatCompletionPort chat,
            CitationSupportVerifier citationVerifier,
            QaPolicy policy,
            Clock clock) {
        this.authorizedSpaces = Objects.requireNonNull(authorizedSpaces);
        this.retrieval = Objects.requireNonNull(retrieval);
        this.persistence = Objects.requireNonNull(persistence);
        this.audits = Objects.requireNonNull(audits);
        this.digests = Objects.requireNonNull(digests);
        this.sanitizer = Objects.requireNonNull(sanitizer);
        this.chat = Objects.requireNonNull(chat);
        this.citationVerifier = Objects.requireNonNull(citationVerifier);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    public QaOutcome ask(AskQuestionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
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

        Set<UUID> spaces;
        try {
            spaces = Set.copyOf(Objects.requireNonNull(
                    authorizedSpaces.resolve(command.actorId(), requestTime),
                    "authorized spaces must not be null"));
        } catch (RuntimeException unavailable) {
            return complete(state, command.actorId(), new QaOutcome.Failed(
                    command.requestId(), "AUTHORIZED_SCOPE_UNAVAILABLE"));
        }
        if (spaces.isEmpty()) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.NO_AUTHORIZED_SPACE, false));
        }
        if (spaces.size() > policy.maxAuthorizedSpaces()) {
            return complete(state, command.actorId(), new QaOutcome.Failed(
                    command.requestId(), "AUTHORIZED_SCOPE_TOO_LARGE"));
        }

        Optional<QaPersistencePort.RequestState> retrieving = persistence.transition(
                command.requestId(), state.revision(), QaStatus.RETRIEVING, clock.instant());
        if (retrieving.isEmpty()) return stale(command);
        state = retrieving.orElseThrow();

        EvidencePack pack;
        try {
            pack = retrieval.retrieve(new RetrievalQuery(
                    command.requestId(), command.actorId(), spaces, question.value(), Map.of(),
                    policy.requestedEvidenceCount()));
        } catch (RuntimeException retrievalFailure) {
            return complete(state, command.actorId(), new QaOutcome.Failed(
                    command.requestId(), "RETRIEVAL_FAILED"));
        }
        if (!command.requestId().equals(pack.requestId())) {
            return complete(state, command.actorId(), new QaOutcome.Failed(
                    command.requestId(), "RETRIEVAL_REQUEST_MISMATCH"));
        }
        if (pack.evidence().size() < policy.minEvidenceCount()
                || pack.evidence().stream().mapToDouble(Evidence::score).max().orElse(0.0) <= 0.0) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.INSUFFICIENT_EVIDENCE, pack.degraded()));
        }
        if (hasConflictingEvidence(pack)) {
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

        SanitizationResult sanitized;
        try {
            sanitized = sanitizer.sanitize(evidence);
        } catch (RuntimeException sanitizationFailure) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.UNSAFE_TO_SEND, pack.degraded()));
        }
        if (sanitized.blocked()) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.UNSAFE_TO_SEND, pack.degraded()));
        }
        if (timedOut(deadline)) {
            return complete(state, command.actorId(), new QaOutcome.Failed(command.requestId(), "QA_TIMEOUT"));
        }

        Optional<QaPersistencePort.RequestState> generating = persistence.transition(
                command.requestId(), state.revision(), QaStatus.GENERATING, clock.instant());
        if (generating.isEmpty()) return stale(command);
        state = generating.orElseThrow();
        GroundedAnswerPrompt prompt = new GroundedAnswerPrompt(
                question, sanitized.evidence(), "zh-CN", POLICY_VERSION, PROMPT_VERSION,
                policy.maxModelOutputCodePoints());
        GenerationAttempt attempt = generate(prompt);

        try {
            audits.record(new ModelCallAuditPort.ModelCallAudit(
                    command.requestId(), questionDigest, responseDigest(attempt), attempt.usage(),
                    sanitizationHits(sanitized), attempt.retryCount(), attempt.resultCode(), clock.instant()));
        } catch (RuntimeException auditFailure) {
            return complete(state, command.actorId(), new QaOutcome.Failed(
                    command.requestId(), "MODEL_AUDIT_FAILED"));
        }

        if (attempt.failure() != null) {
            QaOutcome failureOutcome = mapModelFailure(command.requestId(), attempt.failure(), pack.degraded());
            return complete(state, command.actorId(), failureOutcome);
        }
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

        Optional<QaPersistencePort.RequestState> verifying = persistence.transition(
                command.requestId(), state.revision(), QaStatus.VERIFYING, clock.instant());
        if (verifying.isEmpty()) return stale(command);
        state = verifying.orElseThrow();
        if (!citationVerifier.isSupported(candidate, sanitized.evidence())) {
            return complete(state, command.actorId(), new QaOutcome.Refused(
                    command.requestId(), RefusalReason.UNSUPPORTED_CLAIM, pack.degraded()));
        }
        if (timedOut(deadline)) {
            return complete(state, command.actorId(), new QaOutcome.Failed(command.requestId(), "QA_TIMEOUT"));
        }
        return complete(state, command.actorId(), new QaOutcome.Answered(
                command.requestId(), candidate.answer(), candidate.claims(), pack.degraded()));
    }

    public Optional<QaOutcome> find(UUID requestId, UUID actorId) {
        return persistence.find(
                Objects.requireNonNull(requestId, "requestId must not be null"),
                Objects.requireNonNull(actorId, "actorId must not be null"));
    }

    private GenerationAttempt generate(GroundedAnswerPrompt prompt) {
        int retries = 0;
        while (true) {
            try {
                return GenerationAttempt.success(chat.generate(prompt), retries);
            } catch (ModelGatewayException failure) {
                if (isTransient(failure.kind()) && retries < policy.transientRetryLimit()) {
                    retries++;
                    continue;
                }
                return GenerationAttempt.failure(failure, retries);
            } catch (RuntimeException invalidResponse) {
                return GenerationAttempt.failure(
                        new ModelGatewayException(ModelGatewayException.Kind.INVALID_RESPONSE), retries);
            }
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

    private static QaOutcome mapModelFailure(
            UUID requestId, ModelGatewayException failure, boolean degraded) {
        return switch (failure.kind()) {
            case INVALID_RESPONSE -> new QaOutcome.Refused(
                    requestId, RefusalReason.INVALID_MODEL_OUTPUT, degraded);
            case POLICY_VIOLATION -> new QaOutcome.Refused(
                    requestId, RefusalReason.UNSAFE_TO_SEND, degraded);
            case AUTHENTICATION, RATE_LIMITED, TIMEOUT, UNAVAILABLE -> new QaOutcome.Failed(
                    requestId, "MODEL_" + failure.kind().name());
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

    private static boolean isTransient(ModelGatewayException.Kind kind) {
        return kind == ModelGatewayException.Kind.RATE_LIMITED
                || kind == ModelGatewayException.Kind.TIMEOUT
                || kind == ModelGatewayException.Kind.UNAVAILABLE;
    }

    private static boolean timedOut(long deadline) {
        return System.nanoTime() - deadline >= 0;
    }

    private static boolean hasConflictingEvidence(EvidencePack pack) {
        var seen = new HashMap<EvidenceIdentity, String>();
        for (Evidence item : pack.evidence()) {
            var identity = new EvidenceIdentity(
                    item.spaceId(), item.documentId(), item.versionId(), item.nodeId());
            String content = normalizeForComparison(item.title()) + "\n" + normalizeForComparison(item.excerpt());
            String existing = seen.putIfAbsent(identity, content);
            if (existing != null && !existing.equals(content)) return true;
        }
        return false;
    }

    private static String normalizeForComparison(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", " ").trim();
    }

    private record EvidenceIdentity(UUID spaceId, UUID documentId, UUID versionId, String nodeId) {}

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
