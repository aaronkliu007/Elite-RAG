package com.yunjihai.rag.ingestion;

import com.yunjihai.rag.knowledge.DocumentService;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class DocumentServiceDraftGateway implements DocumentDraftGateway {

    private final DocumentService documents;
    private final Clock clock;

    public DocumentServiceDraftGateway(DocumentService documents, Clock clock) {
        this.documents = Objects.requireNonNull(documents);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public DraftResult createDraft(ValidatedUpload command) {
        var result = documents.createOrReuseDraft(
                command.spaceId(),
                command.title(),
                command.blob().objectKey(),
                command.actorId(),
                Instant.now(clock));
        var version = result.version();
        return new DraftResult(
                new DocumentVersionId(version.documentId(), version.versionId()),
                result.created());
    }
}
