package com.yunjihai.rag.ingestion;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AccessSnapshot;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.access.PrincipalMembershipSnapshot;
import com.yunjihai.rag.access.PublicationState;
import com.yunjihai.rag.knowledge.ConcurrentDocumentUpdateException;
import com.yunjihai.rag.knowledge.DocumentAggregate;
import com.yunjihai.rag.knowledge.DocumentContentGateway;
import com.yunjihai.rag.knowledge.DocumentMetadata;
import com.yunjihai.rag.knowledge.DocumentRepository;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadServiceTest {

    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("相同空间重复上传相同字节复用 Blob 和活跃文档版本")
    void deduplicatesBySha256() {
        var blobs = new InMemoryBlobStore();
        var repository = new MemoryDocumentRepository();
        var membership = new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of());
        var access = new AccessDecisionService(new EffectivePrincipalSet(userId -> membership),
                (spaceId, documentId, refs, at) -> Optional.of(
                        new AccessSnapshot(true, false, false, PublicationState.PUBLISHED, null)));
        var documents = new DocumentService(
                repository,
                access,
                DocumentContentGateway.unavailable(),
                (userId, spaceId, at) -> List.of(),
                KnowledgeWriteAuthorization.allowAll());
        var drafts = new DocumentServiceDraftGateway(
                documents,
                Clock.fixed(Instant.parse("2026-09-24T08:00:00Z"), ZoneOffset.UTC));
        var service = service(blobs, drafts, UploadInspection.safe(MediaType.APPLICATION_PDF));

        var first = service.accept(command("a.pdf", MediaType.APPLICATION_PDF, pdfBytes()));
        var second = service.accept(command("b.pdf", MediaType.APPLICATION_PDF, pdfBytes()));

        assertThat(first).isEqualTo(second);
        assertThat(blobs.values).hasSize(1);
        assertThat(repository.values).hasSize(1);
    }

    @Test
    @DisplayName("旧版本内容再次上传时复用原版本，即使最新草稿已引用其他 Blob")
    void deduplicatesAgainstAnyActiveVersionInsteadOfOnlyLatestVersion() {
        var blobs = new InMemoryBlobStore();
        var repository = new MemoryDocumentRepository();
        var membership = new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of());
        var access = new AccessDecisionService(new EffectivePrincipalSet(userId -> membership),
                (spaceId, documentId, refs, at) -> Optional.of(
                        new AccessSnapshot(true, false, false, PublicationState.PUBLISHED, null)));
        var documents = new DocumentService(
                repository,
                access,
                DocumentContentGateway.unavailable(),
                (userId, spaceId, at) -> List.of(),
                KnowledgeWriteAuthorization.allowAll());
        var drafts = new DocumentServiceDraftGateway(
                documents,
                Clock.fixed(Instant.parse("2026-09-24T08:00:00Z"), ZoneOffset.UTC));
        var service = service(blobs, drafts, UploadInspection.safe(MediaType.APPLICATION_PDF));

        var first = service.accept(command("v1.pdf", MediaType.APPLICATION_PDF, pdfBytes()));
        var parsedAt = Instant.parse("2026-09-24T08:01:00Z");
        documents.updateMetadata(
                first.documentId(), new DocumentMetadata("方案", List.of()), ACTOR_ID, parsedAt);
        documents.markParsingSucceeded(first.documentId(), first.versionId(), parsedAt);
        documents.submitForReview(
                first.documentId(), first.versionId(), ACTOR_ID, parsedAt.plusSeconds(1));
        documents.publish(first.documentId(), first.versionId(), ACTOR_ID, parsedAt.plusSeconds(2));
        documents.createNextDraft(
                first.documentId(), "blobs/sha256/different", ACTOR_ID, parsedAt.plusSeconds(3));

        var repeated = service.accept(command("v1-again.pdf", MediaType.APPLICATION_PDF, pdfBytes()));

        assertThat(repeated).isEqualTo(first);
        assertThat(repository.values).hasSize(1);
    }

    @Test
    @DisplayName("空文件、实际超限和扩展名与 MIME 不一致时不留下 Blob")
    void rejectsInvalidSizeAndMediaBeforePersistence() {
        var blobs = new InMemoryBlobStore();
        var drafts = new RecordingDraftGateway();
        var service = new UploadService(
                new UploadPolicy(8, 100, 10_000, 50), new FileSystemUploadSpooler(),
                (path, filename, mediaType) -> UploadInspection.safe(mediaType), blobs, drafts);

        assertThatThrownBy(() -> service.accept(command("empty.md", MediaType.TEXT_MARKDOWN, new byte[0])))
                .isInstanceOf(UploadRejectedException.class);
        assertThatThrownBy(() -> service.accept(command("large.md", MediaType.TEXT_MARKDOWN, new byte[9])))
                .isInstanceOf(UploadRejectedException.class);
        var deceptive = new UploadCommand(
                SPACE_ID, "large.md", "large.md", MediaType.TEXT_MARKDOWN, 8,
                new ByteArrayInputStream(new byte[9]), ACTOR_ID);
        assertThatThrownBy(() -> service.accept(deceptive))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("实际大小");
        assertThatThrownBy(() -> service.accept(command("wrong.pdf", MediaType.IMAGE_PNG, pngBytes())))
                .isInstanceOf(UploadRejectedException.class);
        assertThat(blobs.values).isEmpty();
        assertThat(drafts.created).isZero();
    }

    @Test
    @DisplayName("加密 PDF、ZIP 炸弹和恶意文件均在持久化前被拒绝")
    void rejectsUnsafeInspectionResultsWithoutBlob() {
        var unsafe = new UploadInspection(MediaType.APPLICATION_PDF, true, false, false, "encrypted");
        var bomb = new UploadInspection(
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                false, true, false, "zip bomb");
        var malicious = new UploadInspection(MediaType.IMAGE_PNG, false, false, true, "malware");

        assertRejectedWithoutBlob(command("secret.pdf", MediaType.APPLICATION_PDF, pdfBytes()), unsafe);
        assertRejectedWithoutBlob(command(
                "bomb.docx",
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                new byte[]{1, 2, 3}), bomb);
        assertRejectedWithoutBlob(command("evil.png", MediaType.IMAGE_PNG, pngBytes()), malicious);
    }

    @Test
    @DisplayName("创建文档版本失败时登记延迟清理候选，不立即删除可能已被并发引用的 Blob")
    void schedulesSafeCleanupWhenDatabaseWriteFails() {
        var blobs = new InMemoryBlobStore();
        DocumentDraftGateway failing = command -> {
            throw new IllegalStateException("database unavailable");
        };
        var service = service(blobs, failing, UploadInspection.safe(MediaType.APPLICATION_PDF));

        assertThatThrownBy(() -> service.accept(command("a.pdf", MediaType.APPLICATION_PDF, pdfBytes())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(blobs.values).hasSize(1);
        assertThat(blobs.cleanupCandidates).isEqualTo(1);
    }

    @Test
    @DisplayName("延迟清理登记失败不覆盖原始数据库异常")
    void preservesDatabaseFailureWhenCleanupSchedulingFails() {
        var blobs = new InMemoryBlobStore();
        blobs.failCleanup = true;
        var databaseFailure = new IllegalStateException("database unavailable");
        var service = service(blobs, command -> {
            throw databaseFailure;
        }, UploadInspection.safe(MediaType.APPLICATION_PDF));

        assertThatThrownBy(() -> service.accept(command("a.pdf", MediaType.APPLICATION_PDF, pdfBytes())))
                .isSameAs(databaseFailure)
                .satisfies(error -> assertThat(error.getSuppressed())
                        .extracting(Throwable::getMessage)
                        .containsExactly("cleanup unavailable"));
    }

    @Test
    @DisplayName("声明或检测 MIME 为通配符时拒绝上传")
    void rejectsWildcardMediaTypes() {
        var blobs = new InMemoryBlobStore();
        var drafts = new RecordingDraftGateway();
        var service = service(blobs, drafts, UploadInspection.safe(MediaType.ALL));

        assertThatThrownBy(() -> service.accept(command("a.pdf", MediaType.ALL, pdfBytes())))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("MIME");
        var detectedWildcard = service(blobs, drafts, UploadInspection.safe(MediaType.parseMediaType("application/*")));
        assertThatThrownBy(() -> detectedWildcard.accept(command("a.pdf", MediaType.APPLICATION_PDF, pdfBytes())))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("MIME");
        assertThat(blobs.values).isEmpty();
    }

    private static void assertRejectedWithoutBlob(UploadCommand command, UploadInspection inspection) {
        var blobs = new InMemoryBlobStore();
        var service = service(blobs, new RecordingDraftGateway(), inspection);
        assertThatThrownBy(() -> service.accept(command)).isInstanceOf(UploadRejectedException.class);
        assertThat(blobs.values).isEmpty();
    }

    private static UploadService service(
            BlobStore blobs,
            DocumentDraftGateway drafts,
            UploadInspection inspection) {
        return new UploadService(
                UploadPolicy.defaults(), new FileSystemUploadSpooler(),
                (path, filename, mediaType) -> inspection, blobs, drafts);
    }

    private static UploadCommand command(String filename, MediaType mediaType, byte[] bytes) {
        return new UploadCommand(
                SPACE_ID, filename, filename, mediaType, bytes.length,
                new ByteArrayInputStream(bytes), ACTOR_ID);
    }

    private static byte[] pdfBytes() {
        return "%PDF-1.7 safe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    }

    private static final class RecordingDraftGateway implements DocumentDraftGateway {
        private int created;

        @Override
        public DocumentVersionId createDraft(ValidatedUpload command) {
            created++;
            return new DocumentVersionId(UUID.randomUUID(), UUID.randomUUID());
        }
    }

    private static final class InMemoryBlobStore implements BlobStore {
        private final Map<String, byte[]> values = new HashMap<>();
        private int cleanupCandidates;
        private boolean failCleanup;

        @Override
        public StoredBlob putIfAbsent(
                String sha256, InputStream stream, long length, MediaType mediaType) {
            try {
                var bytes = stream.readAllBytes();
                var created = values.putIfAbsent(sha256, bytes) == null;
                return new StoredBlob(sha256, "blobs/sha256/" + sha256, length, mediaType, created);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public void scheduleCleanup(StoredBlob blob) {
            if (failCleanup) {
                throw new IllegalStateException("cleanup unavailable");
            }
            cleanupCandidates++;
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
    }
}
