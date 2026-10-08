package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.AccessSnapshot;
import com.yunjihai.rag.access.AclCommandRepository;
import com.yunjihai.rag.access.AclService;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.access.PrincipalMembershipSnapshot;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.access.PublicationState;
import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditEvent;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.ingestion.BlobStore;
import com.yunjihai.rag.ingestion.CanonicalDocumentBody;
import com.yunjihai.rag.ingestion.CanonicalDocumentV1;
import com.yunjihai.rag.ingestion.CanonicalNode;
import com.yunjihai.rag.ingestion.DefaultUploadSecurityScanner;
import com.yunjihai.rag.ingestion.DocumentServiceDraftGateway;
import com.yunjihai.rag.ingestion.DocumentVersionId;
import com.yunjihai.rag.ingestion.FileSystemUploadSpooler;
import com.yunjihai.rag.ingestion.IngestionJobRepository;
import com.yunjihai.rag.ingestion.IngestionJobService;
import com.yunjihai.rag.ingestion.IngestionLease;
import com.yunjihai.rag.ingestion.IngestionRequest;
import com.yunjihai.rag.ingestion.IngestionState;
import com.yunjihai.rag.ingestion.ParserError;
import com.yunjihai.rag.ingestion.ParserStatus;
import com.yunjihai.rag.ingestion.ParsedDocumentStore;
import com.yunjihai.rag.ingestion.StoredBlob;
import com.yunjihai.rag.ingestion.UploadCommand;
import com.yunjihai.rag.ingestion.UploadPolicy;
import com.yunjihai.rag.ingestion.UploadService;
import com.yunjihai.rag.knowledge.ConcurrentDocumentUpdateException;
import com.yunjihai.rag.knowledge.DocumentAggregate;
import com.yunjihai.rag.knowledge.DocumentContent;
import com.yunjihai.rag.knowledge.DocumentContentGateway;
import com.yunjihai.rag.knowledge.DocumentRepository;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.KnowledgeController;
import com.yunjihai.rag.knowledge.KnowledgeSpaceService;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FoundationAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final UUID AUTHORIZED_USER = UUID.fromString("00000000-0000-0000-0000-000000001001");
    private static final UUID UNAUTHORIZED_USER = UUID.fromString("00000000-0000-0000-0000-000000001002");
    private static final byte[] REPRESENTATIVE_PDF = ("%PDF-1.4\n"
            + "1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n"
            + "2 0 obj<< /Type /Pages /Kids [3 0 R] /Count 1 >>endobj\n"
            + "3 0 obj<< /Type /Page /Parent 2 0 R >>endobj\n"
            + "trailer<< /Root 1 0 R >>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path temporaryDirectory;

    @Test
    void uploadedPdfMustBeReviewedBeforePublicationAndRemainsAuditableAfterArchive() {
        var fixture = new Fixture(temporaryDirectory);
        var space = fixture.spaces.createSpace("交付方案库", AUTHORIZED_USER, NOW);
        var authorizedPrincipal = new PrincipalRef(PrincipalType.USER, AUTHORIZED_USER);
        fixture.aclService.grantSpace(
                AUTHORIZED_USER, space.id(), authorizedPrincipal, null, NOW);

        var version = fixture.uploads.accept(new UploadCommand(
                space.id(),
                "某客户数字化转型方案",
                "客户方案.pdf",
                MediaType.APPLICATION_PDF,
                REPRESENTATIVE_PDF.length,
                new ByteArrayInputStream(REPRESENTATIVE_PDF),
                AUTHORIZED_USER));
        fixture.aclService.grantDocument(
                AUTHORIZED_USER,
                space.id(),
                version.documentId(),
                authorizedPrincipal,
                null,
                NOW);
        fixture.contents.putSource(version, "客户方案.pdf", REPRESENTATIVE_PDF);

        fixture.jobs.enqueue(new IngestionRequest(
                version,
                AUTHORIZED_USER,
                fixture.blobs.onlyBlob().objectKey(),
                "客户方案.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                REPRESENTATIVE_PDF.length,
                fixture.blobs.onlyBlob().sha256()));
        var lease = fixture.jobs.claimNext("parser-acceptance-1").orElseThrow();
        fixture.jobs.complete(lease, fixture.parsedResult(lease.jobId()));

        var reviewed = fixture.documents.findById(version.documentId()).orElseThrow();
        assertThat(reviewed.latestVersion().status()).isEqualTo(DocumentStatus.IN_REVIEW);
        assertThat(reviewed.currentPublishedVersionId()).isEmpty();
        assertThat(fixture.documentService.listAuthorizedDocuments(
                AUTHORIZED_USER, space.id(), NOW)).isEmpty();

        fixture.documentService.publish(
                version.documentId(), version.versionId(), AUTHORIZED_USER, NOW.plusSeconds(1));

        assertThat(fixture.documentService.listAuthorizedDocuments(
                AUTHORIZED_USER, space.id(), NOW.plusSeconds(2)))
                .extracting(DocumentAggregate::id)
                .containsExactly(version.documentId());
        assertThat(fixture.controller.getDocument(
                AUTHORIZED_USER, version.documentId(), NOW.plusSeconds(2)).title())
                .isEqualTo("某客户数字化转型方案");
        assertThat(new String(fixture.controller.getParsedContent(
                AUTHORIZED_USER, version.documentId(), NOW.plusSeconds(2)).bytes(), StandardCharsets.UTF_8))
                .contains("交付范围", "验收标准");
        assertThat(fixture.controller.downloadSource(
                AUTHORIZED_USER, version.documentId(), NOW.plusSeconds(2)).bytes())
                .isEqualTo(REPRESENTATIVE_PDF);

        assertThatThrownBy(() -> fixture.documentService.listAuthorizedDocuments(
                UNAUTHORIZED_USER, space.id(), NOW.plusSeconds(2)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> fixture.controller.getDocument(
                UNAUTHORIZED_USER, version.documentId(), NOW.plusSeconds(2)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> fixture.controller.downloadSource(
                UNAUTHORIZED_USER, version.documentId(), NOW.plusSeconds(2)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        fixture.aclService.revokeDocument(
                AUTHORIZED_USER,
                space.id(),
                version.documentId(),
                authorizedPrincipal,
                NOW.plusSeconds(2));
        assertThat(fixture.documentService.listAuthorizedDocuments(
                AUTHORIZED_USER, space.id(), NOW.plusSeconds(2))).isEmpty();
        assertThatThrownBy(() -> fixture.controller.getDocument(
                AUTHORIZED_USER, version.documentId(), NOW.plusSeconds(2)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        fixture.aclService.grantDocument(
                AUTHORIZED_USER,
                space.id(),
                version.documentId(),
                authorizedPrincipal,
                null,
                NOW.plusSeconds(2));

        fixture.documentService.archive(version.documentId(), AUTHORIZED_USER, NOW.plusSeconds(3));

        assertThat(fixture.documentService.listAuthorizedDocuments(
                AUTHORIZED_USER, space.id(), NOW.plusSeconds(4))).isEmpty();
        assertThatThrownBy(() -> fixture.documentService.listAuthorizedDocuments(
                UNAUTHORIZED_USER, space.id(), NOW.plusSeconds(4)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> fixture.controller.getDocument(
                AUTHORIZED_USER, version.documentId(), NOW.plusSeconds(4)))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThat(fixture.auditEvents)
                .extracting(AuditEvent::action)
                .containsAll(EnumSet.of(
                        AuditAction.UPLOAD,
                        AuditAction.PARSE,
                        AuditAction.PUBLISH,
                        AuditAction.VIEW,
                        AuditAction.DOWNLOAD,
                        AuditAction.ACL_CHANGE,
                        AuditAction.ARCHIVE));
    }

    private static final class Fixture {
        private final MemoryDocumentRepository documents = new MemoryDocumentRepository();
        private final MemoryBlobStore blobs = new MemoryBlobStore();
        private final MemoryContentStore contents = new MemoryContentStore();
        private final MemoryAclRepository acl = new MemoryAclRepository();
        private final MemoryIngestionJobRepository ingestionJobs = new MemoryIngestionJobRepository();
        private final List<AuditEvent> auditEvents = new ArrayList<>();
        private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        private final AuditService audit = new AuditService(auditEvents::add, clock);
        private final KnowledgeWriteAuthorization writeAuthorization =
                (actorId, spaceId, documentId, action) -> {
                    if (!AUTHORIZED_USER.equals(actorId)) {
                        throw new AccessDeniedAsNotFoundException();
                    }
                };
        private final DocumentService documentService;
        private final KnowledgeSpaceService spaces;
        private final AclService aclService;
        private final UploadService uploads;
        private final IngestionJobService jobs;
        private final KnowledgeController controller;

        private Fixture(Path temporaryDirectory) {
            var membership = new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of());
            var access = new AccessDecisionService(
                    new EffectivePrincipalSet(userId -> membership),
                    (spaceId, documentId, principals, at) ->
                            accessSnapshot(spaceId, documentId, principals, at));
            documentService = new DocumentService(
                    documents,
                    access,
                    contents,
                    (userId, spaceId, at) -> documents.publishedFor(spaceId),
                    (userId, spaceId, at) -> {
                        var principal = new PrincipalRef(PrincipalType.USER, userId);
                        if (!acl.isWithinSpaceScope(spaceId, principal, at)) {
                            throw new AccessDeniedAsNotFoundException();
                        }
                    },
                    writeAuthorization,
                    audit);
            spaces = new KnowledgeSpaceService(space -> { }, writeAuthorization);
            aclService = new AclService(
                    acl,
                    writeAuthorization,
                    (spaceId, documentId) -> documents.findById(documentId)
                            .map(document -> document.spaceId().equals(spaceId))
                            .orElse(false),
                    audit);
            var policy = UploadPolicy.defaults();
            uploads = new UploadService(
                    policy,
                    new FileSystemUploadSpooler(temporaryDirectory.resolve("quarantine")),
                    new DefaultUploadSecurityScanner(policy, file -> false),
                    blobs,
                    new DocumentServiceDraftGateway(documentService, clock),
                    audit);
            jobs = new IngestionJobService(
                    ingestionJobs,
                    contents,
                    documentService,
                    audit,
                    clock,
                    Duration.ofMinutes(5));
            controller = new KnowledgeController(spaces, documentService);
        }

        private Optional<AccessSnapshot> accessSnapshot(
                UUID spaceId, UUID documentId, Set<PrincipalRef> principals, Instant at) {
            var document = documents.findById(documentId).orElse(null);
            var publicationState = document == null
                    ? PublicationState.DRAFT
                    : document.archived()
                    ? PublicationState.ARCHIVED
                    : document.currentPublishedVersionId().isPresent()
                    ? PublicationState.PUBLISHED
                    : PublicationState.IN_REVIEW;
            return Optional.of(acl.snapshot(
                    spaceId, documentId, principals, publicationState, at));
        }

        private CanonicalDocumentV1 parsedResult(UUID jobId) {
            return new CanonicalDocumentV1(
                    "canonical-document-v1",
                    jobId,
                    ParserStatus.SUCCEEDED,
                    new CanonicalDocumentBody(
                            "客户方案.pdf",
                            MediaType.APPLICATION_PDF_VALUE,
                            1,
                            List.of(
                                    new CanonicalNode(
                                            "section-1", "HEADING", "交付范围", null, 1, null, null, 0.99),
                                    new CanonicalNode(
                                            "paragraph-1", "PARAGRAPH", "验收标准", "section-1", 1, null, null, 0.98))),
                    List.of());
        }
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
        public Optional<DocumentVersionRef> findActiveVersionBySpaceAndSourceBlob(
                UUID spaceId, String sourceBlobId) {
            return values.values().stream()
                    .filter(document -> document.spaceId().equals(spaceId))
                    .filter(document -> !document.archived())
                    .flatMap(document -> document.versions().stream()
                            .filter(version -> version.sourceBlobId().equals(sourceBlobId))
                            .map(version -> new DocumentVersionRef(document.id(), version.id())))
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

        private List<DocumentAggregate> publishedFor(UUID spaceId) {
            return values.values().stream()
                    .filter(document -> document.spaceId().equals(spaceId))
                    .filter(document -> !document.archived())
                    .filter(document -> document.currentPublishedVersionId().isPresent())
                    .toList();
        }
    }

    private static final class MemoryAclRepository implements AclCommandRepository {
        private final Set<SpaceGrant> spaces = new java.util.HashSet<>();
        private final Set<DocumentGrant> documents = new java.util.HashSet<>();
        private final Set<UUID> restrictedDocuments = new java.util.HashSet<>();

        @Override
        public boolean isWithinSpaceScope(UUID spaceId, PrincipalRef principal, Instant at) {
            return spaces.stream().anyMatch(grant -> grant.spaceId.equals(spaceId)
                    && grant.principal.equals(principal)
                    && grant.activeAt(at));
        }

        @Override
        public void grantSpace(UUID spaceId, PrincipalRef principal, Instant validUntil, Instant at) {
            spaces.removeIf(grant -> grant.spaceId.equals(spaceId)
                    && grant.principal.equals(principal));
            spaces.add(new SpaceGrant(spaceId, principal, validUntil));
        }

        @Override
        public void grantDocument(
                UUID spaceId,
                UUID documentId,
                PrincipalRef principal,
                Instant validUntil,
                Instant at) {
            restrictedDocuments.add(documentId);
            documents.removeIf(grant -> grant.documentId.equals(documentId)
                    && grant.principal.equals(principal));
            documents.add(new DocumentGrant(spaceId, documentId, principal, validUntil));
        }

        @Override
        public void revokeSpace(UUID spaceId, PrincipalRef principal, Instant at) {
            spaces.removeIf(grant -> grant.spaceId.equals(spaceId)
                    && grant.principal.equals(principal));
        }

        @Override
        public void revokeDocument(
                UUID spaceId, UUID documentId, PrincipalRef principal, Instant at) {
            documents.removeIf(grant -> grant.spaceId.equals(spaceId)
                    && grant.documentId.equals(documentId)
                    && grant.principal.equals(principal));
        }

        private AccessSnapshot snapshot(
                UUID spaceId,
                UUID documentId,
                Set<PrincipalRef> principals,
                PublicationState publicationState,
                Instant at) {
            var spaceGranted = principals.stream()
                    .anyMatch(principal -> isWithinSpaceScope(spaceId, principal, at));
            var restricted = restrictedDocuments.contains(documentId);
            var documentGranted = documents.stream()
                    .anyMatch(grant -> grant.spaceId.equals(spaceId)
                            && grant.documentId.equals(documentId)
                            && principals.contains(grant.principal)
                            && grant.activeAt(at));
            return new AccessSnapshot(
                    spaceGranted, restricted, documentGranted, publicationState, null);
        }

        private record SpaceGrant(UUID spaceId, PrincipalRef principal, Instant validUntil) {
            private boolean activeAt(Instant at) {
                return validUntil == null || at.isBefore(validUntil);
            }
        }

        private record DocumentGrant(
                UUID spaceId, UUID documentId, PrincipalRef principal, Instant validUntil) {
            private boolean activeAt(Instant at) {
                return validUntil == null || at.isBefore(validUntil);
            }
        }
    }

    private static final class MemoryBlobStore implements BlobStore {
        private StoredBlob blob;

        @Override
        public StoredBlob putIfAbsent(
                String sha256, java.io.InputStream stream, long length, MediaType mediaType) {
            try {
                var bytes = stream.readAllBytes();
                if (bytes.length != length) {
                    throw new IllegalStateException("Blob 长度不一致");
                }
                blob = new StoredBlob(sha256, "sha256/" + sha256, length, mediaType, true);
                return blob;
            } catch (IOException exception) {
                throw new IllegalStateException("无法读取验收 Blob", exception);
            }
        }

        @Override
        public void scheduleCleanup(StoredBlob ignored) {
        }

        private StoredBlob onlyBlob() {
            return blob;
        }
    }

    private static final class MemoryContentStore implements ParsedDocumentStore, DocumentContentGateway {
        private final Map<DocumentVersionId, DocumentContent> sources = new HashMap<>();
        private final Map<DocumentVersionId, DocumentContent> parsed = new HashMap<>();

        private void putSource(DocumentVersionId version, String filename, byte[] bytes) {
            sources.put(version, new DocumentContent(MediaType.APPLICATION_PDF_VALUE, filename, bytes));
        }

        @Override
        public void replace(DocumentVersionId version, CanonicalDocumentBody document, Instant at) {
            var markdown = document.nodes().stream()
                    .map(CanonicalNode::text)
                    .reduce((left, right) -> left + "\n\n" + right)
                    .orElse("");
            parsed.put(version, new DocumentContent(
                    MediaType.TEXT_MARKDOWN_VALUE,
                    document.sourceFilename() + ".md",
                    markdown.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public DocumentContent readParsed(UUID documentId, UUID versionId) {
            return parsed.get(new DocumentVersionId(documentId, versionId));
        }

        @Override
        public DocumentContent readSource(UUID documentId, UUID versionId) {
            return sources.get(new DocumentVersionId(documentId, versionId));
        }
    }

    private static final class MemoryIngestionJobRepository implements IngestionJobRepository {
        private final Map<UUID, Entry> values = new HashMap<>();

        @Override
        public void enqueue(UUID jobId, IngestionRequest request, Instant at) {
            values.put(jobId, new Entry(request, IngestionState.QUEUED, 0, null, null));
        }

        @Override
        public Optional<IngestionLease> claimNext(String workerId, Instant now, Instant leaseUntil) {
            return values.entrySet().stream()
                    .filter(entry -> entry.getValue().state == IngestionState.QUEUED)
                    .findFirst()
                    .map(entry -> {
                        var leaseToken = UUID.randomUUID();
                        var current = entry.getValue();
                        entry.setValue(new Entry(
                                current.request, IngestionState.LEASED, 1, leaseToken, leaseUntil));
                        return new IngestionLease(
                                entry.getKey(), leaseToken, 1, workerId, leaseUntil, current.request);
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
            transition(jobId, leaseToken, IngestionState.RETRY_WAIT);
        }

        @Override
        public void fail(UUID jobId, UUID leaseToken, ParserError error, Instant at) {
            transition(jobId, leaseToken, IngestionState.FAILED);
        }

        private void transition(UUID jobId, UUID leaseToken, IngestionState next) {
            var current = values.get(jobId);
            if (current == null || current.state != IngestionState.LEASED
                    || !leaseToken.equals(current.leaseToken)) {
                throw new IllegalStateException("入库租约已失效");
            }
            values.put(jobId, new Entry(current.request, next, current.attempts, null, null));
        }

        private record Entry(
                IngestionRequest request,
                IngestionState state,
                int attempts,
                UUID leaseToken,
                Instant leaseUntil) {
        }
    }
}
