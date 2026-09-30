package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.retrieval.projection.ProjectionDocumentLock;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Transaction-scoped PostgreSQL advisory lock shared by projection producers and consumers. */
public final class JdbcProjectionDocumentLock implements ProjectionDocumentLock {
    private static final long NAMESPACE = 0x50524f4a444f434cL;
    private final JdbcClient jdbc;

    public JdbcProjectionDocumentLock(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public <T> T withDocumentLock(UUID documentId, Supplier<T> action) {
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(action);
        requireTransaction();
        long key = documentId.getMostSignificantBits()
                ^ Long.rotateLeft(documentId.getLeastSignificantBits(), 1)
                ^ NAMESPACE;
        try {
            jdbc.sql("SELECT pg_advisory_xact_lock(:lockKey)")
                    .param("lockKey", key)
                    .query((row, number) -> Boolean.TRUE)
                    .single();
            return action.get();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PROJECTION_DOCUMENT_LOCK_REQUIRES_TRANSACTION");
        }
    }
}
