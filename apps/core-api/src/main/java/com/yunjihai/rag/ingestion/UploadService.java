package com.yunjihai.rag.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;

public final class UploadService {

    private final UploadPolicy policy;
    private final UploadSpooler spooler;
    private final UploadSecurityScanner scanner;
    private final BlobStore blobStore;
    private final DocumentDraftGateway drafts;

    public UploadService(
            UploadPolicy policy,
            UploadSpooler spooler,
            UploadSecurityScanner scanner,
            BlobStore blobStore,
            DocumentDraftGateway drafts) {
        this.policy = Objects.requireNonNull(policy);
        this.spooler = Objects.requireNonNull(spooler);
        this.scanner = Objects.requireNonNull(scanner);
        this.blobStore = Objects.requireNonNull(blobStore);
        this.drafts = Objects.requireNonNull(drafts);
    }

    public DocumentVersionId accept(UploadCommand command) {
        policy.validateMetadata(command);
        try (var isolated = spooler.spool(command.stream(), policy.maxBytes())) {
            if (isolated.length() != command.declaredLength()) {
                throw new UploadRejectedException("声明大小与实际大小不一致");
            }
            var inspection = scanner.inspect(
                    isolated.path(), command.originalFilename(), command.declaredMediaType());
            policy.validateInspection(command, inspection);

            StoredBlob blob;
            try (var input = Files.newInputStream(isolated.path())) {
                blob = blobStore.putIfAbsent(
                        isolated.sha256(), input, isolated.length(), inspection.detectedMediaType());
            }
            try {
                return drafts.createDraft(new ValidatedUpload(
                        command.spaceId(), command.title(), command.originalFilename(), command.actorId(), blob));
            } catch (RuntimeException exception) {
                try {
                    blobStore.scheduleCleanup(blob);
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取隔离上传文件失败", exception);
        }
    }
}
