package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.identity.SessionVersionStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

/** PostgreSQL-backed session state, keyed by the internal user identifier. */
public final class JdbcSessionVersionStore implements SessionVersionStore {
    private final JdbcClient jdbc;

    public JdbcSessionVersionStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public long currentVersion(UUID userId) {
        try {
            return jdbc.sql("SELECT session_version FROM app_user WHERE id = :userId")
                    .param("userId", userId).query(Long.class).optional().orElse(0L);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public long advanceVersion(UUID userId) {
        try {
            return jdbc.sql("UPDATE app_user SET session_version = session_version + 1, updated_at = now() "
                            + "WHERE id = :userId RETURNING session_version")
                    .param("userId", userId).query(Long.class).optional()
                    .orElseThrow(() -> new IllegalStateException("USER_NOT_FOUND"));
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public boolean isEnabled(UUID userId) {
        try {
            return jdbc.sql("SELECT enabled FROM app_user WHERE id = :userId")
                    .param("userId", userId).query(Boolean.class).optional().orElse(false);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void setEnabled(UUID userId, boolean enabled) {
        try {
            int changed = jdbc.sql("UPDATE app_user SET enabled = :enabled, updated_at = now() "
                            + "WHERE id = :userId")
                    .param("userId", userId).param("enabled", enabled).update();
            if (changed == 0) throw new IllegalStateException("USER_NOT_FOUND");
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
