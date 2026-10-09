package com.yunjihai.rag.ingestion;

import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.DocumentMetadata;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class DocumentServiceDraftGateway implements DocumentDraftGateway {

    private final DocumentService documents;
    private final Clock clock;

    public DocumentServiceDraftGateway(DocumentService documents, Clock clock) {
        this.documents = Objects.requireNonNull(documents);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void authorize(UUID spaceId, UUID actorId) {
        documents.requireCreateDocument(actorId, spaceId);
    }

    @Override
    public DraftResult createDraft(ValidatedUpload command) {
        var now = Instant.now(clock);
        var result = documents.createOrReuseDraft(
                command.spaceId(),
                command.title(),
                new DocumentMetadata(command.classification(), command.tags()),
                command.blob().objectKey(),
                command.actorId(),
                now);
        var version = result.version();
        return new DraftResult(
                new DocumentVersionId(version.documentId(), version.versionId()),
                result.created());
    }
}
