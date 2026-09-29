package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.identity.SessionVersionStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;

/** PostgreSQL-backed session state, keyed by the stable external user identifier. */
public final class JdbcSessionVersionStore implements SessionVersionStore {
    private final JdbcClient jdbc;

    public JdbcSessionVersionStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public long currentVersion(String userExternalId) {
        try {
            return jdbc.sql("SELECT session_version FROM app_user WHERE external_id = :externalId")
                    .param("externalId", userExternalId).query(Long.class).optional().orElse(0L);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public long advanceVersion(String userExternalId) {
        try {
            return jdbc.sql("UPDATE app_user SET session_version = session_version + 1, updated_at = now() "
                            + "WHERE external_id = :externalId RETURNING session_version")
                    .param("externalId", userExternalId).query(Long.class).optional()
                    .orElseThrow(() -> new IllegalStateException("USER_NOT_FOUND"));
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public boolean isEnabled(String userExternalId) {
        try {
            return jdbc.sql("SELECT enabled FROM app_user WHERE external_id = :externalId")
                    .param("externalId", userExternalId).query(Boolean.class).optional().orElse(false);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void setEnabled(String userExternalId, boolean enabled) {
        try {
            int changed = jdbc.sql("UPDATE app_user SET enabled = :enabled, updated_at = now() "
                            + "WHERE external_id = :externalId")
                    .param("externalId", userExternalId).param("enabled", enabled).update();
            if (changed == 0) throw new IllegalStateException("USER_NOT_FOUND");
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
