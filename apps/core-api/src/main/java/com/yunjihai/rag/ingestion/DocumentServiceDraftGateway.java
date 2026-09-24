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
    public DocumentVersionId createDraft(ValidatedUpload command) {
        var document = documents.createDraft(
                command.spaceId(),
                command.title(),
                command.blob().objectKey(),
                command.actorId(),
                Instant.now(clock));
        return new DocumentVersionId(document.id(), document.latestVersion().id());
    }
}
