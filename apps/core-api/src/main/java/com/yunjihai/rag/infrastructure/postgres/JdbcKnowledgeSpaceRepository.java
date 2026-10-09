package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.knowledge.KnowledgeSpace;
import com.yunjihai.rag.knowledge.KnowledgeSpaceRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class JdbcKnowledgeSpaceRepository implements KnowledgeSpaceRepository {
    private final JdbcClient jdbc;

    public JdbcKnowledgeSpaceRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(KnowledgeSpace space) {
        Objects.requireNonNull(space);
        try {
            jdbc.sql("""
                    INSERT INTO knowledge_space (id, name, description, revision, created_by, created_at)
                    VALUES (:id, :name, :description, :revision, :createdBy, :createdAt)
                    """)
                    .param("id", space.id())
                    .param("name", space.name())
                    .param("description", space.description())
                    .param("revision", space.revision())
                    .param("createdBy", space.createdBy())
                    .param("createdAt", OffsetDateTime.ofInstant(space.createdAt(), ZoneOffset.UTC))
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<KnowledgeSpace> findById(UUID spaceId) {
        Objects.requireNonNull(spaceId);
        try {
            return jdbc.sql("""
                            SELECT id, name, description, revision, created_by, created_at
                            FROM knowledge_space WHERE id = :spaceId
                            """)
                    .param("spaceId", spaceId)
                    .query(JdbcKnowledgeSpaceRepository::mapSpace)
                    .optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public boolean lockForDocumentCreation(UUID spaceId) {
        Objects.requireNonNull(spaceId);
        try {
            return jdbc.sql("""
                            SELECT id
                              FROM knowledge_space
                             WHERE id = :spaceId
                             FOR KEY SHARE
                            """)
                    .param("spaceId", spaceId)
                    .query(UUID.class)
                    .optional()
                    .isPresent();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public List<KnowledgeSpace> listAuthorized(UUID userId, int offset, int limit) {
        Objects.requireNonNull(userId);
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("invalid page bounds");
        try {
            return jdbc.sql("""
                            WITH RECURSIVE effective_principal(principal_type, principal_id, parent_id) AS (
                                SELECT 'USER'::text, account.id, NULL::uuid
                                FROM app_user account
                                WHERE account.id = :userId AND account.enabled
                                UNION
                                SELECT 'DEPARTMENT'::text, department.id, department.parent_id
                                FROM org_membership membership
                                JOIN app_user account ON account.id = membership.user_id AND account.enabled
                                JOIN org_unit department ON department.id = membership.org_unit_id AND department.enabled
                                WHERE membership.user_id = :userId
                                UNION
                                SELECT 'DEPARTMENT'::text, parent.id, parent.parent_id
                                FROM effective_principal current
                                JOIN org_unit parent ON parent.id = current.parent_id AND parent.enabled
                                WHERE current.principal_type = 'DEPARTMENT'
                            )
                            SELECT space.id, space.name, space.description, space.revision,
                                   space.created_by, space.created_at
                            FROM knowledge_space space
                            WHERE EXISTS (
                                SELECT 1 FROM system_role_assignment assignment
                                JOIN app_user account ON account.id = assignment.user_id AND account.enabled
                                WHERE assignment.user_id = :userId
                                  AND assignment.role = 'SYSTEM_ADMIN' AND assignment.revoked_at IS NULL
                            ) OR EXISTS (
                                SELECT 1 FROM space_role_assignment assignment
                                JOIN app_user account ON account.id = assignment.user_id AND account.enabled
                                WHERE assignment.user_id = :userId AND assignment.space_id = space.id
                                  AND assignment.revoked_at IS NULL
                            ) OR EXISTS (
                                SELECT 1 FROM acl_entry acl
                                JOIN effective_principal principal
                                  ON principal.principal_type = acl.principal_type
                                 AND principal.principal_id = acl.principal_id
                                WHERE acl.space_id = space.id AND acl.scope = 'SPACE'
                                  AND acl.revoked_at IS NULL
                                  AND (acl.expires_at IS NULL OR acl.expires_at > now())
                            )
                            ORDER BY space.created_at DESC, space.id DESC
                            OFFSET :offset LIMIT :limit
                            """)
                    .param("userId", userId)
                    .param("offset", offset)
                    .param("limit", limit)
                    .query(JdbcKnowledgeSpaceRepository::mapSpace)
                    .list();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public boolean replace(long expectedRevision, KnowledgeSpace space) {
        Objects.requireNonNull(space);
        if (expectedRevision < 0 || space.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("revision must advance exactly once");
        }
        try {
            return jdbc.sql("""
                            UPDATE knowledge_space
                            SET name = :name, description = :description, revision = :newRevision
                            WHERE id = :id AND revision = :expectedRevision
                            """)
                    .param("name", space.name())
                    .param("description", space.description())
                    .param("newRevision", space.revision())
                    .param("id", space.id())
                    .param("expectedRevision", expectedRevision)
                    .update() == 1;
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static KnowledgeSpace mapSpace(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        return new KnowledgeSpace(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getString("description"),
                row.getLong("revision"),
                row.getObject("created_by", UUID.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
