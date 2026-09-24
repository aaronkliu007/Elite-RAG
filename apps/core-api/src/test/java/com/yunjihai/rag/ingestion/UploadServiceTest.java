package com.yunjihai.rag.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadServiceTest {

    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("相同字节只保存一个不可变 Blob，但分别创建文档版本")
    void deduplicatesBySha256() {
        var blobs = new InMemoryBlobStore();
        var drafts = new RecordingDraftGateway();
        var service = service(blobs, drafts, UploadInspection.safe(MediaType.APPLICATION_PDF));

        var first = service.accept(command("a.pdf", MediaType.APPLICATION_PDF, pdfBytes()));
        var second = service.accept(command("b.pdf", MediaType.APPLICATION_PDF, pdfBytes()));

        assertThat(first).isNotEqualTo(second);
        assertThat(blobs.values).hasSize(1);
        assertThat(drafts.created).isEqualTo(2);
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
}
