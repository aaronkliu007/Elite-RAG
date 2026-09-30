package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.knowledge.DocumentAggregate;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import com.yunjihai.rag.retrieval.projection.ProjectionCutoverLock;
import com.yunjihai.rag.retrieval.projection.ProjectionDocumentLock;
import com.yunjihai.rag.retrieval.projection.ProjectionEventRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Emits compact projection references inside the caller's business transaction. */
public final class JdbcProjectionChangePublisher implements ProjectionChangePublisher {
    private final JdbcClient jdbc;
    private final ProjectionEventRepository events;
    private final ProjectionDocumentLock documents;
    private final ProjectionCutoverLock cutover;

    public JdbcProjectionChangePublisher(
            JdbcClient jdbc,
            ProjectionEventRepository events,
            ProjectionDocumentLock documents,
            ProjectionCutoverLock cutover) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.events = Objects.requireNonNull(events);
        this.documents = Objects.requireNonNull(documents);
        this.cutover = Objects.requireNonNull(cutover);
    }

    @Override
    public void documentChanged(DocumentAggregate document, UUID versionId, Instant at) {
        Objects.requireNonNull(document);
        Objects.requireNonNull(at);
        cutover.withShared(() -> documents.withDocumentLock(document.id(), () -> {
            events.enqueueDocument(
                    document.spaceId(), document.id(), versionId, document.revision(), at);
            return null;
        }));
    }

    @Override
    public void spaceAclChanged(UUID spaceId, Instant at) {
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(at);
        cutover.withShared(() -> {
            events.enqueueSpace(spaceId, at);
            return null;
        });
    }

    @Override
    public void documentAclChanged(UUID spaceId, UUID documentId, Instant at) {
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(at);
        cutover.withShared(() -> documents.withDocumentLock(documentId, () -> {
            var state = loadDocumentState(spaceId, documentId);
            events.enqueueDocument(spaceId, documentId, state.versionId(), state.revision(), at);
            return null;
        }));
    }

    private DocumentProjectionState loadDocumentState(UUID spaceId, UUID documentId) {
        try {
            return jdbc.sql("""
                    SELECT current_published_version_id, revision
                    FROM document
                    WHERE id = :documentId AND space_id = :spaceId
                    """)
                    .param("documentId", documentId)
                    .param("spaceId", spaceId)
                    .query((row, number) -> new DocumentProjectionState(
                            row.getObject("current_published_version_id", UUID.class),
                            row.getLong("revision")))
                    .single();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private record DocumentProjectionState(UUID versionId, long revision) { }
}
