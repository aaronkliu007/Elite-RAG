package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.identity.local.UserAccount;
import com.yunjihai.rag.identity.local.UserAccountStore;
import com.yunjihai.rag.identity.local.UsernameConflictException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcUserAccountStore implements UserAccountStore {
    private static final String SELECT_ACCOUNT = """
            SELECT u.id, COALESCE(c.username_normalized, u.external_id) AS username_normalized, u.display_name, u.enabled, u.session_version,
                   u.created_at, u.updated_at
            FROM app_user u
            LEFT JOIN local_credential c ON c.user_id = u.id
            """;

    private final JdbcClient jdbc;

    public JdbcUserAccountStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(UserAccount account, String provider, String subject) {
        try {
            jdbc.sql("""
                            INSERT INTO app_user
                                (id, external_id, display_name, enabled, session_version, created_at, updated_at)
                            VALUES (:id, :externalId, :displayName, :enabled, :sessionVersion, :createdAt, :updatedAt)
                            """)
                    .param("id", account.id())
                    .param("externalId", "local:" + subject)
                    .param("displayName", account.displayName())
                    .param("enabled", account.enabled())
                    .param("sessionVersion", account.sessionVersion())
                    .param("createdAt", utc(account.createdAt()))
                    .param("updatedAt", utc(account.updatedAt()))
                    .update();
            jdbc.sql("""
                            INSERT INTO external_identity
                                (id, user_id, provider, subject, created_at, updated_at)
                            VALUES (:id, :userId, :provider, :subject, :createdAt, :updatedAt)
                            """)
                    .param("id", UuidV7.generate(account.createdAt()))
                    .param("userId", account.id())
                    .param("provider", provider)
                    .param("subject", subject)
                    .param("createdAt", utc(account.createdAt()))
                    .param("updatedAt", utc(account.updatedAt()))
                    .update();
        } catch (DataAccessException failure) {
            var translated = PostgresPersistenceExceptionTranslator.translate(failure);
            if ("UNIQUE_CONSTRAINT".equals(translated.code())) {
                throw new UsernameConflictException();
            }
            throw translated;
        }
    }

    @Override
    public Optional<UserAccount> findById(UUID userId) {
        try {
            return jdbc.sql(SELECT_ACCOUNT + " WHERE u.id = :userId")
                    .param("userId", userId)
                    .query(JdbcUserAccountStore::map)
                    .optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public List<UserAccount> list() {
        try {
            return jdbc.sql(SELECT_ACCOUNT + " ORDER BY COALESCE(c.username_normalized, u.external_id)")
                    .query(JdbcUserAccountStore::map)
                    .list();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void setEnabled(UUID userId, boolean enabled, Instant at) {
        try {
            int changed = jdbc.sql("UPDATE app_user SET enabled = :enabled, updated_at = :at WHERE id = :userId")
                    .param("userId", userId)
                    .param("enabled", enabled)
                    .param("at", utc(at))
                    .update();
            if (changed == 0) {
                throw new IllegalStateException("USER_NOT_FOUND");
            }
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static UserAccount map(ResultSet row, int rowNumber) throws SQLException {
        return new UserAccount(
                row.getObject("id", UUID.class),
                row.getString("username_normalized"),
                row.getString("display_name"),
                row.getBoolean("enabled"),
                row.getLong("session_version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
