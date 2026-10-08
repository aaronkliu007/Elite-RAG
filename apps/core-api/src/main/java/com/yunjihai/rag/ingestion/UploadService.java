package com.yunjihai.rag.ingestion;

import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.api.idempotency.IdempotencyScope;
import com.yunjihai.rag.api.idempotency.IdempotencyService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class UploadService {

    private final UploadPolicy policy;
    private final UploadSpooler spooler;
    private final UploadSecurityScanner scanner;
    private final BlobStore blobStore;
    private final DocumentDraftGateway drafts;
    private final Function<IngestionRequest, UUID> enqueue;
    private final IdempotencyService idempotency;
    private final AuditSink audit;

    public UploadService(
            UploadPolicy policy,
            UploadSpooler spooler,
            UploadSecurityScanner scanner,
            BlobStore blobStore,
            DocumentDraftGateway drafts,
            IngestionJobService jobs,
            IdempotencyService idempotency,
            AuditSink audit) {
        this(policy, spooler, scanner, blobStore, drafts, jobs::enqueue, idempotency, audit);
    }

    UploadService(
            UploadPolicy policy,
            UploadSpooler spooler,
            UploadSecurityScanner scanner,
            BlobStore blobStore,
            DocumentDraftGateway drafts,
            Function<IngestionRequest, UUID> enqueue,
            IdempotencyService idempotency,
            AuditSink audit) {
        this.policy = Objects.requireNonNull(policy);
        this.spooler = Objects.requireNonNull(spooler);
        this.scanner = Objects.requireNonNull(scanner);
        this.blobStore = Objects.requireNonNull(blobStore);
        this.drafts = Objects.requireNonNull(drafts);
        this.enqueue = Objects.requireNonNull(enqueue);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.audit = Objects.requireNonNull(audit);
    }

    public DocumentVersionId accept(UploadCommand command, String idempotencyKey) {
        policy.validateMetadata(command);
        try (var isolated = spooler.spool(command.stream(), policy.maxBytes())) {
            if (isolated.length() != command.declaredLength()) {
                throw new UploadRejectedException("声明大小与实际大小不一致");
            }
            var inspection = scanner.inspect(
                    isolated.path(), command.originalFilename(), command.declaredMediaType());
            policy.validateInspection(command, inspection);
            var scope = new IdempotencyScope(command.actorId(), "UPLOAD_DOCUMENT", idempotencyKey);
            var requestHash = requestHash(command, isolated.sha256());
            var writtenBlob = new AtomicReference<StoredBlob>();
            try {
                return idempotency.execute(scope, requestHash, DocumentVersionId.class, () -> {
                    StoredBlob blob;
                    try (var input = Files.newInputStream(isolated.path())) {
                        blob = blobStore.putIfAbsent(
                                isolated.sha256(), input, isolated.length(), inspection.detectedMediaType());
                        writtenBlob.set(blob);
                    } catch (IOException exception) {
                        throw new IllegalStateException("读取隔离上传文件失败", exception);
                    }
                    var draft = drafts.createDraft(new ValidatedUpload(
                            command.spaceId(), command.title(), command.originalFilename(), command.actorId(), blob));
                    var version = draft.version();
                    if (draft.created()) {
                        enqueue.apply(new IngestionRequest(
                                version,
                                command.actorId(),
                                blob.objectKey(),
                                command.originalFilename(),
                                blob.mediaType().toString(),
                                blob.length(),
                                blob.sha256()));
                    }
                    audit.record(new AuditCommand(
                            command.actorId(),
                            AuditAction.UPLOAD,
                            "DOCUMENT_VERSION",
                            version.versionId(),
                            AuditOutcome.SUCCEEDED,
                            Map.of(
                                    "spaceId", command.spaceId().toString(),
                                    "versionId", version.versionId().toString())));
                    return version;
                });
            } catch (RuntimeException exception) {
                var blob = writtenBlob.get();
                if (blob != null) {
                    try {
                        blobStore.scheduleCleanup(blob);
                    } catch (RuntimeException cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                throw exception;
            }
        }
    }

    private static String requestHash(UploadCommand command, String sha256) {
        var fields = new String[] {
                command.spaceId().toString(),
                command.title(),
                command.originalFilename(),
                command.declaredMediaType().toString(),
                Long.toString(command.declaredLength()),
                sha256
        };
        int length = 0;
        var encoded = new byte[fields.length][];
        for (int index = 0; index < fields.length; index++) {
            encoded[index] = Objects.requireNonNull(fields[index]).getBytes(StandardCharsets.UTF_8);
            length += Integer.BYTES + encoded[index].length;
        }
        var canonical = ByteBuffer.allocate(length);
        for (var field : encoded) canonical.putInt(field.length).put(field);
        return IdempotencyService.sha256(canonical.array());
    }
}
