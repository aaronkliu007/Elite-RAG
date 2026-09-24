package com.yunjihai.rag.ingestion;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AccessSnapshot;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.access.PrincipalMembershipSnapshot;
import com.yunjihai.rag.access.PublicationState;
import com.yunjihai.rag.audit.AuditEvent;
import com.yunjihai.rag.audit.AuditEventStore;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.knowledge.ConcurrentDocumentUpdateException;
import com.yunjihai.rag.knowledge.DocumentAggregate;
import com.yunjihai.rag.knowledge.DocumentContentGateway;
import com.yunjihai.rag.knowledge.DocumentRepository;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.knowledge.ParseStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");

    @Test
    void expiredLeaseCanBeReclaimedAndIncrementsAttempt() {
        var fixture = new Fixture();
        fixture.jobs.enqueue(fixture.request);

        var first = fixture.jobs.claimNext("worker-a").orElseThrow();
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(fixture.repository.claimNext(
                "worker-b", NOW.plusSeconds(29), NOW.plusSeconds(59))).isEmpty();

        var reclaimed = fixture.repository.claimNext(
                "worker-b", NOW.plusSeconds(31), NOW.plusSeconds(61)).orElseThrow();

        assertThat(reclaimed.jobId()).isEqualTo(first.jobId());
        assertThat(reclaimed.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(reclaimed.workerId()).isEqualTo("worker-b");
        assertThat(reclaimed.attempt()).isEqualTo(2);
    }

    @Test
    void invalidParserResultLeavesDocumentAndParsedContentUnchanged() {
        var fixture = new Fixture();
        fixture.jobs.enqueue(fixture.request);
        var lease = fixture.jobs.claimNext("worker-a").orElseThrow();
        var wrongJob = fixture.success(UUID.randomUUID());

        assertThatThrownBy(() -> fixture.jobs.complete(lease, wrongJob))
                .isInstanceOf(InvalidParserResponseException.class);

        var document = fixture.documents.findById(fixture.document.id()).orElseThrow();
        assertThat(document.latestVersion().status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(document.latestVersion().parseStatus()).isEqualTo(ParseStatus.PENDING);
        assertThat(document.currentPublishedVersionId()).isEmpty();
        assertThat(fixture.parsed.values).isEmpty();
        assertThat(fixture.repository.state(lease.jobId())).isEqualTo(IngestionState.LEASED);
    }

    @Test
    void semanticallyInvalidNodeGraphIsRejectedBeforeAnyWrite() {
        var fixture = new Fixture();
        fixture.jobs.enqueue(fixture.request);
        var lease = fixture.jobs.claimNext("worker-a").orElseThrow();
        var invalid = new CanonicalDocumentV1(
                "canonical-document-v1",
                lease.jobId(),
                ParserStatus.SUCCEEDED,
                new CanonicalDocumentBody(
                        fixture.request.filename(),
                        fixture.request.mediaType(),
                        1,
                        List.of(
                                new CanonicalNode(
                                        "duplicate", "PARAGRAPH", "一", null, 1, null, null, 1.0),
                                new CanonicalNode(
                                        "duplicate", "PARAGRAPH", "二", "missing", 2, null, null, 1.0))),
                List.of());

        assertThatThrownBy(() -> fixture.jobs.complete(lease, invalid))
                .isInstanceOf(InvalidParserResponseException.class)
                .hasMessageContaining("节点");

        var document = fixture.documents.findById(fixture.document.id()).orElseThrow();
        assertThat(document.latestVersion().parseStatus()).isEqualTo(ParseStatus.PENDING);
        assertThat(fixture.parsed.values).isEmpty();
        assertThat(fixture.repository.state(lease.jobId())).isEqualTo(IngestionState.LEASED);
    }

    @Test
    void completeStoresNodesAndMovesOnlyToInReview() {
        var fixture = new Fixture();
        fixture.jobs.enqueue(fixture.request);
        var lease = fixture.jobs.claimNext("worker-a").orElseThrow();

        fixture.jobs.complete(lease, fixture.success(lease.jobId()));

        var document = fixture.documents.findById(fixture.document.id()).orElseThrow();
        assertThat(document.latestVersion().parseStatus()).isEqualTo(ParseStatus.SUCCEEDED);
        assertThat(document.latestVersion().status()).isEqualTo(DocumentStatus.IN_REVIEW);
        assertThat(document.currentPublishedVersionId()).isEmpty();
        assertThat(fixture.parsed.values.get(fixture.request.documentVersion()).nodes())
                .singleElement()
                .satisfies(node -> assertThat(node.text()).isEqualTo("正文"));
        assertThat(fixture.repository.state(lease.jobId())).isEqualTo(IngestionState.SUCCEEDED);
        assertThat(fixture.auditEvents).singleElement()
                .satisfies(event -> assertThat(event.metadata()).doesNotContainKeys("body", "text", "content"));
    }

    @Test
    void workerConvertsStructuredFailureIntoFailedJobAndParseStatus() {
        var fixture = new Fixture();
        fixture.jobs.enqueue(fixture.request);
        var worker = new IngestionWorker(
                fixture.jobs,
                lease -> new CanonicalDocumentV1(
                        "canonical-document-v1",
                        lease.jobId(),
                        ParserStatus.FAILED,
                        null,
                        List.of(new ParserError(ParserErrorCode.ENCRYPTED, "加密文档", false))));

        assertThat(worker.runOnce("worker-a")).isTrue();

        var document = fixture.documents.findById(fixture.document.id()).orElseThrow();
        assertThat(document.latestVersion().parseStatus()).isEqualTo(ParseStatus.FAILED);
        assertThat(document.latestVersion().status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(fixture.repository.onlyState()).isEqualTo(IngestionState.FAILED);
    }

    @Test
    void retryableFailureDoesNotBecomeAnImmediateTerminalFailure() {
        var fixture = new Fixture();
        fixture.jobs.enqueue(fixture.request);
        var worker = new IngestionWorker(
                fixture.jobs,
                lease -> new CanonicalDocumentV1(
                        "canonical-document-v1",
                        lease.jobId(),
                        ParserStatus.FAILED,
                        null,
                        List.of(new ParserError(
                                ParserErrorCode.TIMEOUT, "解析超时", true))));

        assertThat(worker.runOnce("worker-a")).isTrue();

        var document = fixture.documents.findById(fixture.document.id()).orElseThrow();
        assertThat(document.latestVersion().parseStatus()).isEqualTo(ParseStatus.PENDING);
        assertThat(fixture.repository.onlyState()).isEqualTo(IngestionState.RETRY_WAIT);
        assertThat(fixture.repository.claimNext(
                "worker-b", NOW.plusSeconds(29), NOW.plusSeconds(59))).isEmpty();
        assertThat(fixture.repository.claimNext(
                "worker-b", NOW.plusSeconds(30), NOW.plusSeconds(60)).orElseThrow().attempt())
                .isEqualTo(2);
    }

    @Test
    void retryableFailureBecomesTerminalAfterConfiguredAttemptLimit() {
        var fixture = new Fixture();
        fixture.jobs.enqueue(fixture.request);
        var error = new ParserError(ParserErrorCode.TIMEOUT, "解析超时", true);

        var first = fixture.jobs.claimNext("worker-a").orElseThrow();
        fixture.jobs.fail(first, error);
        var second = fixture.repository.claimNext(
                "worker-b", NOW.plusSeconds(30), NOW.plusSeconds(60)).orElseThrow();
        fixture.jobs.fail(second, error);
        var third = fixture.repository.claimNext(
                "worker-c", NOW.plusSeconds(60), NOW.plusSeconds(90)).orElseThrow();
        fixture.jobs.fail(third, error);

        var document = fixture.documents.findById(fixture.document.id()).orElseThrow();
        assertThat(document.latestVersion().parseStatus()).isEqualTo(ParseStatus.FAILED);
        assertThat(fixture.repository.onlyState()).isEqualTo(IngestionState.FAILED);
    }

    @Test
    void workerDoesNotMaskTransactionalCompletionFailureWithSecondFailAttempt() {
        var fixture = new Fixture(event -> {
            throw new IllegalStateException("audit unavailable");
        });
        fixture.jobs.enqueue(fixture.request);
        var worker = new IngestionWorker(
                fixture.jobs,
                lease -> fixture.success(lease.jobId()));

        assertThatThrownBy(() -> worker.runOnce("worker-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
    }

    private static final class Fixture {
        private final MemoryDocumentRepository documents = new MemoryDocumentRepository();
        private final DocumentAggregate document;
        private final IngestionRequest request;
        private final MemoryIngestionJobRepository repository = new MemoryIngestionJobRepository();
        private final MemoryParsedDocumentStore parsed = new MemoryParsedDocumentStore();
        private final List<AuditEvent> auditEvents = new ArrayList<>();
        private final IngestionJobService jobs;

        private Fixture() {
            this(null);
        }

        private Fixture(AuditEventStore eventStore) {
            var documentService = documentService(documents);
            document = documentService.createDraft(SPACE_ID, "方案", "blob-801", ACTOR_ID, NOW);
            request = new IngestionRequest(
                    new DocumentVersionId(document.id(), document.latestVersion().id()),
                    ACTOR_ID,
                    "blob-801",
                    "方案.md",
                    "text/markdown",
                    4,
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            var audit = new AuditService(
                    eventStore == null ? auditEvents::add : eventStore,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            jobs = new IngestionJobService(
                    repository,
                    parsed,
                    documentService,
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    Duration.ofSeconds(30));
        }

        private CanonicalDocumentV1 success(UUID jobId) {
            return new CanonicalDocumentV1(
                    "canonical-document-v1",
                    jobId,
                    ParserStatus.SUCCEEDED,
                    new CanonicalDocumentBody(
                            request.filename(),
                            request.mediaType(),
                            1,
                            List.of(new CanonicalNode(
                                    "node-1", "PARAGRAPH", "正文", null, 1, null, null, 1.0))),
                    List.of());
        }
    }

    private static DocumentService documentService(DocumentRepository repository) {
        var membership = new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of());
        var access = new AccessDecisionService(new EffectivePrincipalSet(userId -> membership),
                (spaceId, documentId, refs, at) -> Optional.of(
                        new AccessSnapshot(true, true, true, PublicationState.PUBLISHED, null)));
        return new DocumentService(
                repository,
                access,
                DocumentContentGateway.unavailable(),
                (userId, spaceId, at) -> List.of(),
                KnowledgeWriteAuthorization.allowAll());
    }

    private static final class MemoryDocumentRepository implements DocumentRepository {
        private final Map<UUID, DocumentAggregate> values = new HashMap<>();

        @Override
        public void insert(DocumentAggregate document) {
            values.put(document.id(), document);
        }

        @Override
        public Optional<DocumentAggregate> findById(UUID documentId) {
            return Optional.ofNullable(values.get(documentId));
        }

        @Override
        public Optional<DocumentAggregate> findActiveBySpaceAndSourceBlob(
                UUID spaceId, String sourceBlobId) {
            return values.values().stream()
                    .filter(document -> document.spaceId().equals(spaceId))
                    .filter(document -> !document.archived())
                    .filter(document -> document.latestVersion().sourceBlobId().equals(sourceBlobId))
                    .findFirst();
        }

        @Override
        public void replace(DocumentAggregate document, long expectedRevision) {
            values.compute(document.id(), (id, current) -> {
                if (current == null || current.revision() != expectedRevision) {
                    throw new ConcurrentDocumentUpdateException();
                }
                return document;
            });
        }
    }

    private static final class MemoryParsedDocumentStore implements ParsedDocumentStore {
        private final Map<DocumentVersionId, CanonicalDocumentBody> values = new HashMap<>();

        @Override
        public void replace(DocumentVersionId version, CanonicalDocumentBody document, Instant at) {
            values.put(version, document);
        }
    }

    private static final class MemoryIngestionJobRepository implements IngestionJobRepository {
        private final Map<UUID, Entry> values = new HashMap<>();

        @Override
        public void enqueue(UUID jobId, IngestionRequest request, Instant at) {
            values.put(jobId, new Entry(request, IngestionState.QUEUED, 0, null, null, at));
        }

        @Override
        public Optional<IngestionLease> claimNext(String workerId, Instant now, Instant leaseUntil) {
            return values.entrySet().stream()
                    .filter(entry -> entry.getValue().state == IngestionState.QUEUED
                            || entry.getValue().state == IngestionState.RETRY_WAIT
                            && !entry.getValue().nextAttemptAt.isAfter(now)
                            || entry.getValue().state == IngestionState.LEASED
                            && !entry.getValue().leaseUntil.isAfter(now))
                    .findFirst()
                    .map(entry -> {
                        var token = UUID.randomUUID();
                        var current = entry.getValue();
                        var attempt = current.attempts + 1;
                        entry.setValue(new Entry(
                                current.request, IngestionState.LEASED, attempt, token, leaseUntil, null));
                        return new IngestionLease(
                                entry.getKey(), token, attempt, workerId, leaseUntil, current.request);
                    });
        }

        @Override
        public void complete(UUID jobId, UUID leaseToken, Instant at) {
            transition(jobId, leaseToken, IngestionState.SUCCEEDED);
        }

        @Override
        public void retry(
                UUID jobId,
                UUID leaseToken,
                ParserError error,
                Instant nextAttemptAt,
                Instant at) {
            var current = requireLease(jobId, leaseToken);
            values.put(jobId, new Entry(
                    current.request,
                    IngestionState.RETRY_WAIT,
                    current.attempts,
                    null,
                    null,
                    nextAttemptAt));
        }

        @Override
        public void fail(UUID jobId, UUID leaseToken, ParserError error, Instant at) {
            transition(jobId, leaseToken, IngestionState.FAILED);
        }

        private void transition(UUID jobId, UUID leaseToken, IngestionState state) {
            var current = requireLease(jobId, leaseToken);
            values.put(jobId, new Entry(
                    current.request, state, current.attempts, null, null, null));
        }

        private Entry requireLease(UUID jobId, UUID leaseToken) {
            var current = values.get(jobId);
            if (current == null || current.state != IngestionState.LEASED
                    || !leaseToken.equals(current.leaseToken)) {
                throw new IllegalStateException("租约已失效");
            }
            return current;
        }

        private IngestionState state(UUID jobId) {
            return values.get(jobId).state;
        }

        private IngestionState onlyState() {
            return values.values().iterator().next().state;
        }

        private record Entry(
                IngestionRequest request,
                IngestionState state,
                int attempts,
                UUID leaseToken,
                Instant leaseUntil,
                Instant nextAttemptAt) {
        }
    }
}
