package com.yunjihai.rag.solution.generation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record GenerationAttempt(
        UUID id,
        GenerationKey key,
        String inputFingerprint,
        GenerationStatus status,
        int executionCount,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Optional<UUID> retrievalRequestId,
        Set<UUID> evidenceIds,
        Optional<ModelUsage> modelUsage,
        Optional<UUID> resultSectionVersionId,
        Optional<String> failureCode,
        Optional<GenerationStatus> retryCheckpoint) {

    public GenerationAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(inputFingerprint, "inputFingerprint");
        if (!inputFingerprint.equals(key.inputFingerprint())) {
            throw new IllegalArgumentException("generation fingerprint mismatch");
        }
        Objects.requireNonNull(status, "status");
        if (executionCount < 1 || revision < 0) {
            throw new IllegalArgumentException("invalid generation counters");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("generation time moves backwards");
        }
        retrievalRequestId = Objects.requireNonNull(retrievalRequestId, "retrievalRequestId");
        evidenceIds = Set.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        modelUsage = Objects.requireNonNull(modelUsage, "modelUsage");
        resultSectionVersionId = Objects.requireNonNull(
                resultSectionVersionId, "resultSectionVersionId");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        failureCode.ifPresent(GenerationAttempt::requireErrorCode);
        retryCheckpoint = Objects.requireNonNull(retryCheckpoint, "retryCheckpoint");
        if (status == GenerationStatus.FAILED_RETRYABLE && retryCheckpoint.isEmpty()) {
            throw new IllegalArgumentException("retryable failure requires checkpoint");
        }
        validateSnapshot(
                status, retrievalRequestId, evidenceIds, modelUsage,
                resultSectionVersionId, failureCode, retryCheckpoint);
    }

    public static GenerationAttempt start(UUID attemptId, GenerationKey key, Instant at) {
        Objects.requireNonNull(key, "key");
        return new GenerationAttempt(
                attemptId,
                key,
                key.inputFingerprint(),
                GenerationStatus.PENDING,
                1,
                0,
                at,
                at,
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public void validateRequest(GenerationKey candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!key.clientKey().equals(candidate.clientKey())
                || !inputFingerprint.equals(candidate.inputFingerprint())) {
            throw new GenerationRequestConflictException();
        }
    }

    public GenerationAttempt startRetrieval(Instant at) {
        requireStatus(GenerationStatus.PENDING);
        return next(
                GenerationStatus.RETRIEVING, executionCount, at,
                retrievalRequestId, evidenceIds, modelUsage, resultSectionVersionId,
                Optional.empty(), Optional.empty());
    }

    public GenerationAttempt recordEvidence(UUID requestId, Set<UUID> selectedEvidenceIds, Instant at) {
        requireStatus(GenerationStatus.RETRIEVING);
        Objects.requireNonNull(requestId, "requestId");
        var selected = Set.copyOf(Objects.requireNonNull(selectedEvidenceIds, "selectedEvidenceIds"));
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("generation evidence must not be empty");
        }
        return next(
                GenerationStatus.GENERATING, executionCount, at,
                Optional.of(requestId), selected, modelUsage, resultSectionVersionId,
                Optional.empty(), Optional.empty());
    }

    public GenerationAttempt startVerification(ModelUsage usage, Instant at) {
        requireStatus(GenerationStatus.GENERATING);
        if (modelUsage.isPresent()) {
            throw new GenerationStateException();
        }
        return next(
                GenerationStatus.VERIFYING, executionCount, at,
                retrievalRequestId, evidenceIds, Optional.of(Objects.requireNonNull(usage, "usage")),
                resultSectionVersionId, Optional.empty(), Optional.empty());
    }

    public GenerationAttempt heartbeat(Instant at) {
        requireStatus(GenerationStatus.GENERATING);
        return next(GenerationStatus.GENERATING, executionCount, at,
                retrievalRequestId, evidenceIds, modelUsage, resultSectionVersionId,
                Optional.empty(), Optional.empty());
    }

    public GenerationAttempt succeed(UUID sectionVersionId, Instant at) {
        requireStatus(GenerationStatus.VERIFYING);
        if (modelUsage.isEmpty()) {
            throw new GenerationStateException();
        }
        return next(
                GenerationStatus.SUCCEEDED, executionCount, at,
                retrievalRequestId, evidenceIds, modelUsage,
                Optional.of(Objects.requireNonNull(sectionVersionId, "sectionVersionId")),
                Optional.empty(), Optional.empty());
    }

    public GenerationAttempt insufficientEvidence(
            UUID requestId,
            Set<UUID> selectedEvidenceIds,
            Instant at) {
        requireStatus(GenerationStatus.RETRIEVING);
        return next(
                GenerationStatus.INSUFFICIENT_EVIDENCE, executionCount, at,
                Optional.of(Objects.requireNonNull(requestId, "requestId")),
                Set.copyOf(Objects.requireNonNull(selectedEvidenceIds, "selectedEvidenceIds")),
                modelUsage, resultSectionVersionId,
                Optional.of("INSUFFICIENT_EVIDENCE"), Optional.empty());
    }

    public GenerationAttempt failRetryable(String errorCode, Instant at) {
        requireActive();
        requireErrorCode(errorCode);
        return next(
                GenerationStatus.FAILED_RETRYABLE, executionCount, at,
                retrievalRequestId, evidenceIds, modelUsage, resultSectionVersionId,
                Optional.of(errorCode), Optional.of(status));
    }

    public GenerationAttempt failManual(String errorCode, Instant at) {
        requireActive();
        requireErrorCode(errorCode);
        return next(
                GenerationStatus.FAILED_MANUAL, executionCount, at,
                retrievalRequestId, evidenceIds, modelUsage, resultSectionVersionId,
                Optional.of(errorCode), Optional.empty());
    }

    public GenerationAttempt cancel(Instant at) {
        if (!isActive(status) && status != GenerationStatus.FAILED_RETRYABLE) {
            throw new GenerationStateException();
        }
        return next(
                GenerationStatus.CANCELLED, executionCount, at,
                retrievalRequestId, evidenceIds, modelUsage, resultSectionVersionId,
                Optional.of("GENERATION_CANCELLED"), Optional.empty());
    }

    public GenerationAttempt retry(Instant at) {
        requireStatus(GenerationStatus.FAILED_RETRYABLE);
        return next(
                retryCheckpoint.orElseThrow(GenerationStateException::new),
                executionCount + 1,
                at,
                retrievalRequestId,
                evidenceIds,
                modelUsage,
                resultSectionVersionId,
                Optional.empty(),
                Optional.empty());
    }

    public GenerationAttempt exhaustRetries(Instant at) {
        requireStatus(GenerationStatus.FAILED_RETRYABLE);
        return next(GenerationStatus.FAILED_MANUAL, executionCount, at,
                retrievalRequestId, evidenceIds, modelUsage, resultSectionVersionId,
                Optional.of("RETRY_LIMIT_REACHED"), Optional.empty());
    }

    private GenerationAttempt next(
            GenerationStatus nextStatus,
            int nextExecutionCount,
            Instant at,
            Optional<UUID> nextRetrievalRequestId,
            Set<UUID> nextEvidenceIds,
            Optional<ModelUsage> nextModelUsage,
            Optional<UUID> nextResultSectionVersionId,
            Optional<String> nextFailureCode,
            Optional<GenerationStatus> nextRetryCheckpoint) {
        Objects.requireNonNull(at, "at");
        if (at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("generation time moves backwards");
        }
        return new GenerationAttempt(
                id, key, inputFingerprint, nextStatus, nextExecutionCount, revision + 1,
                createdAt, at, nextRetrievalRequestId, nextEvidenceIds, nextModelUsage,
                nextResultSectionVersionId, nextFailureCode, nextRetryCheckpoint);
    }

    private void requireStatus(GenerationStatus expected) {
        if (status != expected) {
            throw new GenerationStateException();
        }
    }

    private void requireActive() {
        if (!isActive(status)) {
            throw new GenerationStateException();
        }
    }

    private static boolean isActive(GenerationStatus value) {
        return value == GenerationStatus.PENDING
                || value == GenerationStatus.RETRIEVING
                || value == GenerationStatus.GENERATING
                || value == GenerationStatus.VERIFYING;
    }

    private static void validateSnapshot(
            GenerationStatus status,
            Optional<UUID> retrievalRequestId,
            Set<UUID> evidenceIds,
            Optional<ModelUsage> modelUsage,
            Optional<UUID> resultSectionVersionId,
            Optional<String> failureCode,
            Optional<GenerationStatus> retryCheckpoint) {
        if (status != GenerationStatus.FAILED_RETRYABLE && retryCheckpoint.isPresent()) {
            throw new IllegalArgumentException("retry checkpoint is only valid for retryable failure");
        }
        if (status == GenerationStatus.FAILED_RETRYABLE
                && !isActive(retryCheckpoint.orElseThrow())) {
            throw new IllegalArgumentException("retry checkpoint must be active");
        }
        if (status == GenerationStatus.PENDING || status == GenerationStatus.RETRIEVING) {
            if (retrievalRequestId.isPresent() || !evidenceIds.isEmpty() || modelUsage.isPresent()
                    || resultSectionVersionId.isPresent() || failureCode.isPresent()) {
                throw new IllegalArgumentException("pending retrieval state contains later results");
            }
        }
        if (status == GenerationStatus.GENERATING
                && (retrievalRequestId.isEmpty() || evidenceIds.isEmpty() || modelUsage.isPresent()
                || resultSectionVersionId.isPresent() || failureCode.isPresent())) {
            throw new IllegalArgumentException("generating state is incomplete");
        }
        if (status == GenerationStatus.VERIFYING
                && (retrievalRequestId.isEmpty() || evidenceIds.isEmpty() || modelUsage.isEmpty()
                || resultSectionVersionId.isPresent() || failureCode.isPresent())) {
            throw new IllegalArgumentException("verifying state is incomplete");
        }
        if (status == GenerationStatus.SUCCEEDED
                && (retrievalRequestId.isEmpty() || evidenceIds.isEmpty() || modelUsage.isEmpty()
                || resultSectionVersionId.isEmpty() || failureCode.isPresent())) {
            throw new IllegalArgumentException("succeeded state is incomplete");
        }
        if (status == GenerationStatus.INSUFFICIENT_EVIDENCE
                && (retrievalRequestId.isEmpty() || modelUsage.isPresent()
                || resultSectionVersionId.isPresent() || failureCode.isEmpty())) {
            throw new IllegalArgumentException("insufficient evidence state is incomplete");
        }
        if ((status == GenerationStatus.FAILED_MANUAL || status == GenerationStatus.CANCELLED)
                && (failureCode.isEmpty() || resultSectionVersionId.isPresent())) {
            throw new IllegalArgumentException("failed state is incomplete");
        }
        if (status == GenerationStatus.FAILED_RETRYABLE
                && (failureCode.isEmpty() || resultSectionVersionId.isPresent())) {
            throw new IllegalArgumentException("retryable failed state is incomplete");
        }
    }

    private static void requireErrorCode(String errorCode) {
        Objects.requireNonNull(errorCode, "errorCode");
        if (!errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid generation error code");
        }
    }
}
