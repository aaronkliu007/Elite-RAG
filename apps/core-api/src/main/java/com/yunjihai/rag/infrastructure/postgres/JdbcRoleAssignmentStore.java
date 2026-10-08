package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.role.RoleAssignmentStore;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.access.role.SystemRole;
import com.yunjihai.rag.common.UuidV7;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class JdbcRoleAssignmentStore implements RoleAssignmentStore {
    private final JdbcClient jdbc;

    public JdbcRoleAssignmentStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public boolean hasSystemRole(UUID userId, SystemRole role) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(role);
        try {
            return jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1
                                FROM system_role_assignment assignment
                                JOIN app_user account ON account.id = assignment.user_id AND account.enabled
                                WHERE assignment.user_id = :userId
                                  AND assignment.role = :role
                                  AND assignment.revoked_at IS NULL
                            )
                            """)
                    .param("userId", userId)
                    .param("role", role.name())
                    .query(Boolean.class)
                    .single();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public boolean hasAnySystemRole(SystemRole role) {
        Objects.requireNonNull(role);
        try {
            return jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1
                                FROM system_role_assignment assignment
                                JOIN app_user account ON account.id = assignment.user_id AND account.enabled
                                WHERE assignment.role = :role
                                  AND assignment.revoked_at IS NULL
                            )
                            """)
                    .param("role", role.name())
                    .query(Boolean.class)
                    .single();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Set<SpaceRole> activeSpaceRoles(UUID userId, UUID spaceId) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(spaceId);
        try {
            var result = EnumSet.noneOf(SpaceRole.class);
            jdbc.sql("""
                            SELECT assignment.role
                            FROM space_role_assignment assignment
                            JOIN app_user account ON account.id = assignment.user_id AND account.enabled
                            WHERE assignment.user_id = :userId
                              AND assignment.space_id = :spaceId
                              AND assignment.revoked_at IS NULL
                            ORDER BY assignment.role
                            """)
                    .param("userId", userId)
                    .param("spaceId", spaceId)
                    .query(String.class)
                    .list()
                    .stream()
                    .map(SpaceRole::valueOf)
                    .forEach(result::add);
            return Set.copyOf(result);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void grantSystemRole(UUID userId, SystemRole role, UUID actorId, Instant at) {
        requireCommon(userId, actorId, at);
        Objects.requireNonNull(role);
        try {
            jdbc.sql("""
                            INSERT INTO system_role_assignment
                                (id, user_id, role, granted_by, granted_at)
                            VALUES (:id, :userId, :role, :actorId, :at)
                            ON CONFLICT (user_id, role) WHERE revoked_at IS NULL DO NOTHING
                            """)
                    .param("id", UuidV7.generate(at))
                    .param("userId", userId)
                    .param("role", role.name())
                    .param("actorId", actorId)
                    .param("at", timestamp(at))
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void grantSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at) {
        requireSpace(spaceId, userId, role, actorId, at);
        try {
            jdbc.sql("""
                            INSERT INTO space_role_assignment
                                (id, space_id, user_id, role, granted_by, granted_at)
                            VALUES (:id, :spaceId, :userId, :role, :actorId, :at)
                            ON CONFLICT (space_id, user_id, role) WHERE revoked_at IS NULL DO NOTHING
                            """)
                    .param("id", UuidV7.generate(at))
                    .param("spaceId", spaceId)
                    .param("userId", userId)
                    .param("role", role.name())
                    .param("actorId", actorId)
                    .param("at", timestamp(at))
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public void revokeSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at) {
        requireSpace(spaceId, userId, role, actorId, at);
        try {
            jdbc.sql("""
                            UPDATE space_role_assignment
                            SET revoked_by = :actorId, revoked_at = :at
                            WHERE space_id = :spaceId
                              AND user_id = :userId
                              AND role = :role
                              AND revoked_at IS NULL
                            """)
                    .param("actorId", actorId)
                    .param("at", timestamp(at))
                    .param("spaceId", spaceId)
                    .param("userId", userId)
                    .param("role", role.name())
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private static void requireSpace(
            UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at) {
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(role);
        requireCommon(userId, actorId, at);
    }

    private static void requireCommon(UUID userId, UUID actorId, Instant at) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(at);
    }

    private static OffsetDateTime timestamp(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
