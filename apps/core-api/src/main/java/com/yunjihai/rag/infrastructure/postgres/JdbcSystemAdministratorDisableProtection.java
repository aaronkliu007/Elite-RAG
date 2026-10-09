package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.identity.local.SystemAdministratorDisableProtection;
import com.yunjihai.rag.identity.local.LocalIdentityValidationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

public final class JdbcSystemAdministratorDisableProtection
        implements SystemAdministratorDisableProtection {
    private static final long ADMIN_DISABLE_LOCK = 0x594852414741444dL;
    private final JdbcClient jdbc;

    public JdbcSystemAdministratorDisableProtection(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void requireCanDisable(UUID targetUserId, UUID actorId) {
        Objects.requireNonNull(targetUserId);
        Objects.requireNonNull(actorId);
        try {
            jdbc.sql("SELECT pg_advisory_xact_lock(:lockKey)")
                    .param("lockKey", ADMIN_DISABLE_LOCK)
                    .query((row, number) -> Boolean.TRUE)
                    .single();
            var state = jdbc.sql("""
                    SELECT
                        EXISTS (
                            SELECT 1
                            FROM system_role_assignment assignment
                            JOIN app_user account
                              ON account.id = assignment.user_id AND account.enabled
                            WHERE assignment.user_id = :actorId
                              AND assignment.role = 'SYSTEM_ADMIN'
                              AND assignment.revoked_at IS NULL
                        ) AS actor_is_administrator,
                        EXISTS (
                            SELECT 1
                            FROM system_role_assignment assignment
                            JOIN app_user account
                              ON account.id = assignment.user_id AND account.enabled
                            WHERE assignment.user_id = :targetUserId
                              AND assignment.role = 'SYSTEM_ADMIN'
                              AND assignment.revoked_at IS NULL
                        ) AS target_is_administrator,
                        (
                            SELECT count(*)
                            FROM system_role_assignment assignment
                            JOIN app_user account
                              ON account.id = assignment.user_id AND account.enabled
                            WHERE assignment.role = 'SYSTEM_ADMIN'
                              AND assignment.revoked_at IS NULL
                        ) AS active_administrator_count
                    """)
                    .param("actorId", actorId)
                    .param("targetUserId", targetUserId)
                    .query((row, number) -> new AdministratorState(
                            row.getBoolean("actor_is_administrator"),
                            row.getBoolean("target_is_administrator"),
                            row.getLong("active_administrator_count")))
                    .single();
            if (!state.actorIsAdministrator()) {
                throw new LocalIdentityValidationException("当前操作者已失去系统管理员权限");
            }
            if (state.targetIsAdministrator() && state.activeAdministratorCount() <= 1) {
                throw new LocalIdentityValidationException("系统必须至少保留一个已启用的系统管理员");
            }
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private record AdministratorState(
            boolean actorIsAdministrator,
            boolean targetIsAdministrator,
            long activeAdministratorCount) { }
}
