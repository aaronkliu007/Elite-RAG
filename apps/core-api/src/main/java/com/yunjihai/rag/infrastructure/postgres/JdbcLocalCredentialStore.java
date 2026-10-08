package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.identity.local.LocalCredential;
import com.yunjihai.rag.identity.local.LocalCredentialStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcLocalCredentialStore implements LocalCredentialStore {
    private final JdbcClient jdbc;

    public JdbcLocalCredentialStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<LocalCredential> lockForAuthentication(String normalizedUsername) {
        try {
            return jdbc.sql("""
                            SELECT user_id, username_normalized, password_hash, failed_attempts,
                                   failure_window_started_at, locked_until, password_changed_at
                            FROM local_credential
                            WHERE username_normalized = :username
                            FOR UPDATE
                            """)
                    .param("username", normalizedUsername)
                    .query(JdbcLocalCredentialStore::map)
                    .optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void insert(LocalCredential credential) {
        try {
            jdbc.sql("""
                            INSERT INTO local_credential
                                (user_id, username_normalized, password_hash, failed_attempts,
                                 failure_window_started_at, locked_until, password_changed_at,
                                 created_at, updated_at)
                            VALUES (:userId, :username, :passwordHash, :failures,
                                    :windowStartedAt, :lockedUntil, :passwordChangedAt,
                                    :passwordChangedAt, :passwordChangedAt)
                            """)
                    .param("userId", credential.userId())
                    .param("username", credential.normalizedUsername())
                    .param("passwordHash", credential.passwordHash())
                    .param("failures", credential.failedAttempts())
                    .param("windowStartedAt", utcNullable(credential.failureWindowStartedAt()))
                    .param("lockedUntil", utcNullable(credential.lockedUntil()))
                    .param("passwordChangedAt", utc(credential.passwordChangedAt()))
                    .update();
        } catch (DataAccessException failure) {
            var translated = PostgresPersistenceExceptionTranslator.translate(failure);
            if ("UNIQUE_CONSTRAINT".equals(translated.code())) {
                throw new com.yunjihai.rag.identity.local.UsernameConflictException();
            }
            throw translated;
        }
    }

    @Override
    public void updateAuthenticationState(
            UUID userId, int failures, Instant windowStartedAt, Instant lockedUntil, Instant at) {
        update("""
                        UPDATE local_credential
                        SET failed_attempts = :failures,
                            failure_window_started_at = :windowStartedAt,
                            locked_until = :lockedUntil,
                            updated_at = :at
                        WHERE user_id = :userId
                        """, userId, failures, windowStartedAt, lockedUntil, at);
    }

    @Override
    public void resetAuthenticationState(UUID userId, Instant at) {
        update("""
                        UPDATE local_credential
                        SET failed_attempts = 0,
                            failure_window_started_at = NULL,
                            locked_until = NULL,
                            updated_at = :at
                        WHERE user_id = :userId
                        """, userId, 0, null, null, at);
    }

    @Override
    public void replacePassword(UUID userId, String passwordHash, Instant at) {
        try {
            int changed = jdbc.sql("""
                            UPDATE local_credential
                            SET password_hash = :passwordHash,
                                failed_attempts = 0,
                                failure_window_started_at = NULL,
                                locked_until = NULL,
                                password_changed_at = :at,
                                updated_at = :at
                            WHERE user_id = :userId
                            """)
                    .param("userId", userId)
                    .param("passwordHash", passwordHash)
                    .param("at", utc(at))
                    .update();
            requireChanged(changed);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private void update(
            String sql, UUID userId, int failures, Instant windowStartedAt, Instant lockedUntil, Instant at) {
        try {
            int changed = jdbc.sql(sql)
                    .param("userId", userId)
                    .param("failures", failures)
                    .param("windowStartedAt", utcNullable(windowStartedAt))
                    .param("lockedUntil", utcNullable(lockedUntil))
                    .param("at", utc(at))
                    .update();
            requireChanged(changed);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static LocalCredential map(ResultSet row, int rowNumber) throws SQLException {
        OffsetDateTime window = row.getObject("failure_window_started_at", OffsetDateTime.class);
        OffsetDateTime locked = row.getObject("locked_until", OffsetDateTime.class);
        return new LocalCredential(
                row.getObject("user_id", UUID.class),
                row.getString("username_normalized"),
                row.getString("password_hash"),
                row.getInt("failed_attempts"),
                window == null ? null : window.toInstant(),
                locked == null ? null : locked.toInstant(),
                row.getObject("password_changed_at", OffsetDateTime.class).toInstant());
    }

    private static void requireChanged(int changed) {
        if (changed == 0) {
            throw new IllegalStateException("LOCAL_CREDENTIAL_NOT_FOUND");
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static OffsetDateTime utcNullable(Instant instant) {
        return instant == null ? null : utc(instant);
    }
}
