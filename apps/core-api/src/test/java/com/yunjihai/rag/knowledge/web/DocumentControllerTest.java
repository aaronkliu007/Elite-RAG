package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.AccessSnapshot;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.access.PrincipalMembershipSnapshot;
import com.yunjihai.rag.access.PublicationState;
import com.yunjihai.rag.api.idempotency.IdempotencyConflictException;
import com.yunjihai.rag.api.idempotency.IdempotencyScope;
import com.yunjihai.rag.api.idempotency.IdempotencyService;
import com.yunjihai.rag.api.idempotency.IdempotencyStore;
import com.yunjihai.rag.api.idempotency.StoredHttpResult;
import com.yunjihai.rag.knowledge.DocumentAggregate;
import com.yunjihai.rag.knowledge.DocumentContent;
import com.yunjihai.rag.knowledge.DocumentContentGateway;
import com.yunjihai.rag.knowledge.DocumentMetadata;
import com.yunjihai.rag.knowledge.DocumentReview;
import com.yunjihai.rag.knowledge.DocumentReviewStore;
import com.yunjihai.rag.knowledge.DocumentRepository;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.DocumentView;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.knowledge.ParseStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-09T02:00:00Z");
    private static final UUID SPACE = id(1);
    private static final UUID EDITOR = id(2);
    private static final UUID REVIEWER = id(3);
    private static final UUID READER = id(4);
    private static final UUID OUTSIDER = id(5);

    @Test
    void readerSeesOnlyCurrentPublishedVersionWithoutManagementData() {
        var fixture = fixture();

        var detail = fixture.controller.get(authentication(READER), fixture.documentId);

        assertThat(detail.versions()).singleElement().satisfies(version -> {
            assertThat(version.id()).isEqualTo(fixture.publishedVersionId);
            assertThat(version.status()).isEqualTo(DocumentStatus.PUBLISHED);
            assertThat(version.parseErrorCode()).isEmpty();
        });
        assertThat(detail.reviews()).isEmpty();
        assertThat(detail.allowedActions()).containsExactlyInAnyOrder("VIEW", "PREVIEW", "DOWNLOAD");
        assertThat(DocumentDtos.DocumentDetail.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("acl", "sourceBlobId", "internalError");
        assertThat(DocumentView.VersionView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("sourceBlobId");
    }

    @Test
    void listUsesManagementQueryOnlyAfterSpaceAuthorization() {
        var fixture = fixture();

        var managerList = fixture.controller.list(authentication(EDITOR), SPACE);
        var readerList = fixture.controller.list(authentication(READER), SPACE);

        assertThat(managerList).singleElement()
                .satisfies(document -> assertThat(document.versions()).hasSize(2));
        assertThat(readerList).singleElement()
                .satisfies(document -> assertThat(document.versions()).singleElement()
                        .satisfies(version -> assertThat(version.status()).isEqualTo(DocumentStatus.PUBLISHED)));
        assertThatThrownBy(() -> fixture.controller.list(authentication(OUTSIDER), SPACE))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test
    void managerSeesHistoryReviewsParseErrorAndAllowedActions() {
        var fixture = fixture();

        var detail = fixture.controller.get(authentication(EDITOR), fixture.documentId);

        assertThat(detail.versions()).hasSize(2);
        assertThat(detail.versions().getLast().parseStatus()).isEqualTo(ParseStatus.FAILED);
        assertThat(detail.versions().getLast().parseErrorCode()).contains("PARSER_TIMEOUT");
        assertThat(detail.reviews()).singleElement()
                .satisfies(review -> assertThat(review.decision()).isEqualTo("PUBLISHED"));
        assertThat(detail.allowedActions()).contains("VIEW", "EDIT_METADATA");
        assertThat(detail.metadata().classification()).isEqualTo("制度");
    }

    @Test
    void hiddenAndMissingDocumentsHaveTheSameFailure() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.controller.get(authentication(OUTSIDER), fixture.documentId))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> fixture.controller.get(authentication(OUTSIDER), id(999)))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test
    void roleBoundariesRemainFailClosed() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.controller.publish(
                authentication(EDITOR), fixture.documentId, fixture.draftVersionId, "editor-publish"))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> fixture.controller.updateMetadata(
                authentication(REVIEWER), fixture.documentId, "reviewer-edit",
                new DocumentDtos.UpdateMetadataRequest("机密", List.of())))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> fixture.controller.archive(
                authentication(READER), fixture.documentId, "reader-archive"))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test
    void documentMutationsAreIdempotentAndRejectSameKeyWithDifferentInput() {
        var fixture = fixture();
        fixture.repository.resetReplaceCount();
        var request = new DocumentDtos.UpdateMetadataRequest("流程", List.of("质量"));

        var first = fixture.controller.updateMetadata(
                authentication(EDITOR), fixture.documentId, "metadata-key", request);
        var replay = fixture.controller.updateMetadata(
                authentication(EDITOR), fixture.documentId, "metadata-key", request);

        assertThat(replay).isEqualTo(first);
        assertThat(fixture.repository.replaceCount()).isOne();
        assertThatThrownBy(() -> fixture.controller.updateMetadata(
                authentication(EDITOR), fixture.documentId, "metadata-key",
                new DocumentDtos.UpdateMetadataRequest("另一分类", List.of())))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void idempotentReplayRechecksRevokedPermission() {
        var fixture = fixture();
        var request = new DocumentDtos.UpdateMetadataRequest("流程", List.of("质量"));
        fixture.controller.updateMetadata(
                authentication(EDITOR), fixture.documentId, "revoked-replay", request);

        fixture.editors.remove(EDITOR);

        assertThatThrownBy(() -> fixture.controller.updateMetadata(
                authentication(EDITOR), fixture.documentId, "revoked-replay", request))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test
    void listRejectsUnboundedPagination() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.controller.list(authentication(EDITOR), SPACE, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.controller.list(authentication(EDITOR), SPACE, 1, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.controller.list(
                authentication(EDITOR), SPACE, Integer.MAX_VALUE, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void downloadUsesSafeAttachmentFilename() {
        var fixture = fixture();

        var response = fixture.controller.download(authentication(READER), fixture.documentId);

        String disposition = response.getHeaders().getFirst("Content-Disposition");
        assertThat(disposition).contains("attachment").contains("report.pdf");
        assertThat(disposition).doesNotContain("\\", "\r", "\n", "X-Evil", "..");
        assertThat(response.getBody()).containsExactly(4, 5, 6);
    }

    private static Fixture fixture() {
        var repository = new CountingRepository();
        var membership = new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of());
        var access = new AccessDecisionService(new EffectivePrincipalSet(user -> membership),
                (spaceId, documentId, principals, at) -> Optional.of(new AccessSnapshot(
                        READER.equals(principals.iterator().next().id()), false, false,
                        PublicationState.PUBLISHED, null)));
        var reviews = new Reviews();
        var editors = new HashSet<UUID>();
        editors.add(EDITOR);
        KnowledgeWriteAuthorization authorization = (actor, space, document, action) -> {
            boolean editor = editors.contains(actor) && Set.of(
                    KnowledgeWriteAuthorization.Action.CREATE_DOCUMENT,
                    KnowledgeWriteAuthorization.Action.EDIT_DOCUMENT,
                    KnowledgeWriteAuthorization.Action.SUBMIT_FOR_REVIEW).contains(action);
            boolean reviewer = REVIEWER.equals(actor) && Set.of(
                    KnowledgeWriteAuthorization.Action.REVIEW_DOCUMENT,
                    KnowledgeWriteAuthorization.Action.PUBLISH_DOCUMENT).contains(action);
            if (!editor && !reviewer) throw new AccessDeniedAsNotFoundException();
        };
        var content = new DocumentContentGateway() {
            public DocumentContent readParsed(UUID documentId, UUID versionId) {
                return new DocumentContent("text/markdown", "preview.md", new byte[] {1, 2, 3});
            }
            public DocumentContent readSource(UUID documentId, UUID versionId) {
                return new DocumentContent("application/pdf", "..\\report\r\nX-Evil: 1.pdf", new byte[] {4, 5, 6});
            }
        };
        var service = new DocumentService(
                repository, access, content,
                (user, space, at) -> repository.values(),
                (user, space, at) -> {
                    if (OUTSIDER.equals(user)) throw new AccessDeniedAsNotFoundException();
                },
                authorization, command -> null,
                com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher.noop(), reviews);

        DocumentAggregate document = service.createDraft(SPACE, "管理制度", blob(1), EDITOR, NOW.minusSeconds(30));
        UUID published = document.latestVersion().id();
        service.updateMetadata(document.id(), new DocumentMetadata("制度", List.of("质量")), EDITOR, NOW.minusSeconds(29));
        service.markParsingSucceeded(document.id(), published, NOW.minusSeconds(28));
        service.submitForReview(document.id(), published, EDITOR, NOW.minusSeconds(27));
        service.publish(document.id(), published, REVIEWER, NOW.minusSeconds(26));
        document = service.createNextDraft(document.id(), blob(2), EDITOR, NOW.minusSeconds(25));
        UUID draft = document.latestVersion().id();
        service.markParsingFailed(document.id(), draft, NOW.minusSeconds(24));

        var idempotency = new IdempotencyService(
                new MemoryIdempotencyStore(), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24));
        var controller = new DocumentController(
                service, idempotency, new ObjectMapper(),
                version -> version.equals(draft) ? Optional.of("PARSER_TIMEOUT") : Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(controller, repository, editors, document.id(), published, draft);
    }

    private static UsernamePasswordAuthenticationToken authentication(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), "n/a", List.of());
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }

    private static String blob(int suffix) {
        String digest = "%064x".formatted(suffix);
        return "sha256/" + digest.substring(0, 2) + "/" + digest;
    }

    private record Fixture(
            DocumentController controller,
            CountingRepository repository,
            Set<UUID> editors,
            UUID documentId,
            UUID publishedVersionId,
            UUID draftVersionId) { }

    private static final class CountingRepository implements DocumentRepository {
        private final Map<UUID, DocumentAggregate> values = new HashMap<>();
        private int replaceCount;
        public void insert(DocumentAggregate document) { values.put(document.id(), document); }
        public Optional<DocumentAggregate> findById(UUID documentId) {
            return Optional.ofNullable(values.get(documentId));
        }
        public List<DocumentAggregate> listBySpaceId(UUID spaceId) {
            return values.values().stream()
                    .filter(document -> document.spaceId().equals(spaceId) && !document.archived())
                    .toList();
        }
        public Optional<com.yunjihai.rag.knowledge.DocumentVersionRef> findActiveVersionBySpaceAndSourceBlob(
                UUID spaceId, String sourceBlobId) {
            return values.values().stream()
                    .filter(document -> document.spaceId().equals(spaceId) && !document.archived())
                    .flatMap(document -> document.versions().stream()
                            .filter(version -> version.sourceBlobId().equals(sourceBlobId))
                            .map(version -> new com.yunjihai.rag.knowledge.DocumentVersionRef(
                                    document.id(), version.id())))
                    .findFirst();
        }
        @Override
        public void replace(DocumentAggregate document, long expectedRevision) {
            var current = values.get(document.id());
            if (current == null || current.revision() != expectedRevision) {
                throw new com.yunjihai.rag.knowledge.ConcurrentDocumentUpdateException();
            }
            values.put(document.id(), document);
            replaceCount++;
        }
        List<DocumentAggregate> values() {
            return List.copyOf(values.values());
        }
        void resetReplaceCount() { replaceCount = 0; }
        int replaceCount() { return replaceCount; }
    }

    private static final class Reviews implements DocumentReviewStore {
        private final List<DocumentReview> values = new ArrayList<>();
        public void append(DocumentReview review) { values.add(review); }
        public List<DocumentReview> listByDocumentId(UUID documentId) {
            return values.stream().filter(review -> review.documentId().equals(documentId)).toList();
        }
    }

    private static final class MemoryIdempotencyStore implements IdempotencyStore {
        private final Map<IdempotencyScope, Entry> values = new HashMap<>();
        public synchronized Claim claim(IdempotencyScope scope, String hash, Instant now, Instant expiresAt) {
            var value = values.get(scope);
            if (value == null) {
                values.put(scope, new Entry(hash, null));
                return new Claim.Acquired();
            }
            if (!value.hash.equals(hash)) return new Claim.HashMismatch();
            return value.result == null ? new Claim.InProgress() : new Claim.Completed(value.result);
        }
        public synchronized void complete(IdempotencyScope scope, String hash, StoredHttpResult result, Instant at) {
            values.put(scope, new Entry(hash, result));
        }
        public synchronized void release(IdempotencyScope scope, String hash) { values.remove(scope); }
        private record Entry(String hash, StoredHttpResult result) { }
    }
}
