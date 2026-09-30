package com.yunjihai.rag.ingestion;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.knowledge.DocumentService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class IngestionJobService {

    private final IngestionJobRepository repository;
    private final ParsedDocumentStore parsedDocuments;
    private final DocumentService documents;
    private final AuditService audit;
    private final Clock clock;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final Duration retryBaseDelay;

    public IngestionJobService(
            IngestionJobRepository repository,
            ParsedDocumentStore parsedDocuments,
            DocumentService documents,
            AuditService audit,
            Clock clock,
            Duration leaseDuration) {
        this(repository, parsedDocuments, documents, audit, clock, leaseDuration, 3, Duration.ofSeconds(30));
    }

    public IngestionJobService(
            IngestionJobRepository repository,
            ParsedDocumentStore parsedDocuments,
            DocumentService documents,
            AuditService audit,
            Clock clock,
            Duration leaseDuration,
            int maxAttempts,
            Duration retryBaseDelay) {
        this.repository = Objects.requireNonNull(repository);
        this.parsedDocuments = Objects.requireNonNull(parsedDocuments);
        this.documents = Objects.requireNonNull(documents);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
        this.leaseDuration = Objects.requireNonNull(leaseDuration);
        this.maxAttempts = maxAttempts;
        this.retryBaseDelay = Objects.requireNonNull(retryBaseDelay);
        if (leaseDuration.isZero() || leaseDuration.isNegative()
                || retryBaseDelay.isZero() || retryBaseDelay.isNegative()
                || maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("入库租约、重试次数或退避时长无效");
        }
    }

    @Transactional
    public UUID enqueue(IngestionRequest request) {
        Objects.requireNonNull(request);
        var now = Instant.now(clock);
        var jobId = UuidV7.generate(now);
        repository.enqueue(jobId, request, now);
        return jobId;
    }

    @Transactional
    public Optional<IngestionLease> claimNext(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("Worker 标识不能为空");
        }
        var now = Instant.now(clock);
        var claimed = repository.claimNext(workerId, now, now.plus(leaseDuration));
        if (claimed.isPresent() && claimed.orElseThrow().attempt() > maxAttempts) {
            fail(claimed.orElseThrow(), new ParserError(
                    ParserErrorCode.TIMEOUT,
                    "Worker 租约连续过期，已达到尝试上限",
                    false));
            return Optional.empty();
        }
        return claimed;
    }

    @Transactional
    public void complete(IngestionLease lease, CanonicalDocumentV1 result) {
        var now = Instant.now(clock);
        requireActiveLease(lease, now);
        var body = validateSuccess(lease, result);
        parsedDocuments.replace(lease.request().documentVersion(), body, now);
        documents.completeParsing(
                lease.request().documentVersion().documentId(),
                lease.request().documentVersion().versionId(),
                lease.request().actorId(),
                now);
        repository.complete(lease.jobId(), lease.leaseToken(), now);
        audit.record(new AuditCommand(
                lease.request().actorId(),
                AuditAction.PARSE,
                "DOCUMENT_VERSION",
                lease.request().documentVersion().versionId(),
                AuditOutcome.SUCCEEDED,
                Map.of(
                        "jobId", lease.jobId().toString(),
                        "attempt", Integer.toString(lease.attempt()),
                        "versionId", lease.request().documentVersion().versionId().toString())));
    }

    @Transactional
    public void fail(IngestionLease lease, ParserError error) {
        var now = Instant.now(clock);
        requireActiveLease(lease, now);
        Objects.requireNonNull(error);
        if (error.retryable() && lease.attempt() < maxAttempts) {
            var multiplier = 1L << Math.min(lease.attempt() - 1, 8);
            repository.retry(
                    lease.jobId(),
                    lease.leaseToken(),
                    error,
                    now.plus(retryBaseDelay.multipliedBy(multiplier)),
                    now);
            recordParseFailure(lease, error);
            return;
        }
        documents.markParsingFailedForIngestion(
                lease.request().documentVersion().documentId(),
                lease.request().documentVersion().versionId(),
                now);
        repository.fail(lease.jobId(), lease.leaseToken(), error, now);
        recordParseFailure(lease, error);
    }

    private void recordParseFailure(IngestionLease lease, ParserError error) {
        audit.record(new AuditCommand(
                lease.request().actorId(),
                AuditAction.PARSE,
                "DOCUMENT_VERSION",
                lease.request().documentVersion().versionId(),
                AuditOutcome.FAILED,
                Map.of(
                        "jobId", lease.jobId().toString(),
                        "attempt", Integer.toString(lease.attempt()),
                        "errorCode", error.code().name(),
                        "versionId", lease.request().documentVersion().versionId().toString())));
    }

    private static void requireActiveLease(IngestionLease lease, Instant now) {
        Objects.requireNonNull(lease);
        if (!lease.leaseUntil().isAfter(now)) {
            throw new IllegalStateException("入库租约已过期");
        }
    }

    private static CanonicalDocumentBody validateSuccess(
            IngestionLease lease, CanonicalDocumentV1 result) {
        if (result == null
                || !"canonical-document-v1".equals(result.schemaVersion())
                || !lease.jobId().equals(result.jobId())
                || result.status() != ParserStatus.SUCCEEDED
                || result.document() == null
                || result.errors() == null
                || !result.errors().isEmpty()
                || !lease.request().filename().equals(result.document().sourceFilename())
                || !lease.request().mediaType().equals(result.document().mediaType())) {
            throw new InvalidParserResponseException("解析成功结果与入库租约不一致");
        }
        var body = result.document();
        if (body.pageCount() < 1 || body.pageCount() > 500
                || body.nodes() == null || body.nodes().size() > 1_000_000) {
            throw new InvalidParserResponseException("解析结果节点或页数超出限制");
        }
        var nodeIds = new HashSet<String>();
        for (var node : body.nodes()) {
            if (node == null || node.nodeId() == null || node.nodeId().isBlank()
                    || !nodeIds.add(node.nodeId())
                    || node.page() != null && (node.page() < 1 || node.page() > body.pageCount())) {
                throw new InvalidParserResponseException("解析结果节点无效");
            }
        }
        for (var node : body.nodes()) {
            if (node.parentNodeId() != null && !nodeIds.contains(node.parentNodeId())) {
                throw new InvalidParserResponseException("解析结果节点父级不存在");
            }
        }
        return body;
    }
}
