package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.solution.application.IdentityAuthorizationLock;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Objects;
import java.util.UUID;

public final class JdbcIdentityAuthorizationLock implements IdentityAuthorizationLock {
    private final JdbcClient jdbc;
    public JdbcIdentityAuthorizationLock(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    @Override public void lock(UUID actorId) {
        Objects.requireNonNull(actorId);
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("identity authorization lock requires a transaction");
        try {
            // Shared counterpart of JdbcOrganizationReconciliationAdapter's transaction lock.
            jdbc.sql("SELECT pg_advisory_xact_lock_shared(20260929, 3)").query((row, number) -> 1).single();
            jdbc.sql("SELECT id FROM app_user WHERE id = :id AND enabled FOR SHARE")
                    .param("id", actorId).query(UUID.class).optional().orElseThrow(AccessDeniedAsNotFoundException::new);
        } catch (DataAccessException failure) { throw PostgresPersistenceExceptionTranslator.translate(failure); }
    }
}
