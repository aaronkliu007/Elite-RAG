package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.AclCommandRepository;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.common.UuidV7;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/** Mutates active ACL rows while retaining every revoked row as history. */
public final class JdbcAclCommandRepository implements AclCommandRepository {
    private final JdbcClient jdbc;

    public JdbcAclCommandRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public boolean isWithinSpaceScope(UUID spaceId, PrincipalRef principal, Instant at) {
        requireArguments(spaceId, principal, at);
        try {
            return jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM acl_entry
                                WHERE space_id = :spaceId
                                  AND scope = 'SPACE'
                                  AND principal_type = :principalType
                                  AND principal_id = :principalId
                                  AND revoked_at IS NULL
                                  AND (expires_at IS NULL OR expires_at > :at)
                            )
                            """)
                    .param("spaceId", spaceId)
                    .param("principalType", principal.type().name())
                    .param("principalId", principal.id())
                    .param("at", timestamp(at))
                    .query(Boolean.class)
                    .single();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void grantSpace(UUID spaceId, PrincipalRef principal, Instant validUntil, Instant at) {
        requireArguments(spaceId, principal, at);
        executeGrant(spaceId, null, "SPACE", principal, validUntil, at);
    }

    @Override
    public void grantDocument(
            UUID spaceId,
            UUID documentId,
            PrincipalRef principal,
            Instant validUntil,
            Instant at) {
        requireArguments(spaceId, principal, at);
        Objects.requireNonNull(documentId, "documentId must not be null");
        executeGrant(spaceId, documentId, "DOCUMENT", principal, validUntil, at);
    }

    @Override
    public void revokeSpace(UUID spaceId, PrincipalRef principal, Instant at) {
        requireArguments(spaceId, principal, at);
        executeRevoke(spaceId, null, "SPACE", principal, at);
    }

    @Override
    public void revokeDocument(
            UUID spaceId,
            UUID documentId,
            PrincipalRef principal,
            Instant at) {
        requireArguments(spaceId, principal, at);
        Objects.requireNonNull(documentId, "documentId must not be null");
        executeRevoke(spaceId, documentId, "DOCUMENT", principal, at);
    }

    private void executeGrant(
            UUID spaceId,
            UUID documentId,
            String scope,
            PrincipalRef principal,
            Instant validUntil,
            Instant at) {
        String conflictTarget = "SPACE".equals(scope)
                ? "(space_id, principal_type, principal_id) WHERE scope = 'SPACE' AND revoked_at IS NULL"
                : "(document_id, principal_type, principal_id) WHERE scope = 'DOCUMENT' AND revoked_at IS NULL";
        try {
            var statement = jdbc.sql("""
                            INSERT INTO acl_entry
                                (id, space_id, document_id, scope, principal_type, principal_id,
                                 expires_at, created_at, updated_at)
                            VALUES
                                (:id, :spaceId, :documentId, :scope, :principalType, :principalId,
                                 :expiresAt, :at, :at)
                            ON CONFLICT %s
                            DO UPDATE SET expires_at = excluded.expires_at, updated_at = excluded.updated_at
                            """.formatted(conflictTarget))
                    .param("id", UuidV7.generate(at))
                    .param("spaceId", spaceId)
                    .param("scope", scope)
                    .param("principalType", principal.type().name())
                    .param("principalId", principal.id())
                    .param("at", timestamp(at));
            statement = nullable(statement, "documentId", documentId, java.sql.Types.OTHER);
            statement = nullable(statement, "expiresAt",
                    validUntil == null ? null : timestamp(validUntil), java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            statement.update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private void executeRevoke(
            UUID spaceId,
            UUID documentId,
            String scope,
            PrincipalRef principal,
            Instant at) {
        String documentPredicate = documentId == null ? "document_id IS NULL" : "document_id = :documentId";
        try {
            var statement = jdbc.sql("""
                            UPDATE acl_entry
                            SET revoked_at = :at, updated_at = :at
                            WHERE space_id = :spaceId
                              AND scope = :scope
                              AND %s
                              AND principal_type = :principalType
                              AND principal_id = :principalId
                              AND revoked_at IS NULL
                            """.formatted(documentPredicate))
                    .param("at", timestamp(at))
                    .param("spaceId", spaceId)
                    .param("scope", scope)
                    .param("principalType", principal.type().name())
                    .param("principalId", principal.id());
            if (documentId != null) {
                statement = statement.param("documentId", documentId);
            }
            statement.update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static JdbcClient.StatementSpec nullable(
            JdbcClient.StatementSpec statement,
            String name,
            Object value,
            int sqlType) {
        return value == null ? statement.param(name, null, sqlType) : statement.param(name, value);
    }

    private static void requireArguments(UUID spaceId, PrincipalRef principal, Instant at) {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(at, "at must not be null");
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
