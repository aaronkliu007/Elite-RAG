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
import java.util.function.Supplier;

/** Emits compact projection references inside the caller's business transaction. */
public final class JdbcProjectionChangePublisher implements ProjectionChangePublisher {
    private final JdbcClient jdbc;
    private final ProjectionEventRepository events;
    private final ProjectionDocumentLock documents;
    private final ProjectionCutoverLock cutover;
    private final ThreadLocal<Integer> spaceLockDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> documentLockDepth = ThreadLocal.withInitial(() -> 0);

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
    public <T> T withDocumentChangeLock(UUID documentId, Supplier<T> action) {
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(action);
        return cutover.withShared(() -> documents.withDocumentLock(documentId, () -> {
            spaceLockDepth.set(spaceLockDepth.get() + 1);
            documentLockDepth.set(documentLockDepth.get() + 1);
            try {
                return action.get();
            } finally {
                decrement(documentLockDepth);
                decrement(spaceLockDepth);
            }
        }));
    }

    @Override
    public <T> T withSpaceChangeLock(UUID spaceId, Supplier<T> action) {
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(action);
        return cutover.withShared(() -> {
            spaceLockDepth.set(spaceLockDepth.get() + 1);
            try {
                return action.get();
            } finally {
                decrement(spaceLockDepth);
            }
        });
    }

    @Override
    public void documentChanged(DocumentAggregate document, UUID versionId, Instant at) {
        Objects.requireNonNull(document);
        Objects.requireNonNull(at);
        requireDocumentLock();
        events.enqueueDocument(document.spaceId(), document.id(), versionId, document.revision(), at);
    }

    @Override
    public void spaceAclChanged(UUID spaceId, Instant at) {
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(at);
        requireSpaceLock();
        events.enqueueSpace(spaceId, at);
    }

    @Override
    public void documentAclChanged(UUID spaceId, UUID documentId, Instant at) {
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(at);
        requireDocumentLock();
        var state = loadDocumentState(spaceId, documentId);
        events.enqueueDocument(spaceId, documentId, state.versionId(), state.revision(), at);
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

    private void requireDocumentLock() {
        if (documentLockDepth.get() == 0) {
            throw new IllegalStateException("PROJECTION_DOCUMENT_CHANGE_LOCK_REQUIRED");
        }
    }

    private void requireSpaceLock() {
        if (spaceLockDepth.get() == 0) {
            throw new IllegalStateException("PROJECTION_SPACE_CHANGE_LOCK_REQUIRED");
        }
    }

    private static void decrement(ThreadLocal<Integer> depth) {
        int next = depth.get() - 1;
        if (next == 0) depth.remove();
        else depth.set(next);
    }
}
