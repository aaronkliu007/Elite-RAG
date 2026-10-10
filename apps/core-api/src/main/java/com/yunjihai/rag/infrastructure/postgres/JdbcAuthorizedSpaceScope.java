package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.qa.AuthorizedSpaceScopePort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class JdbcAuthorizedSpaceScope implements AuthorizedSpaceScopePort {
    private final JdbcClient jdbc;

    public JdbcAuthorizedSpaceScope(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override
    public Set<UUID> resolve(UUID actorId, Instant at) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try {
            var ids = jdbc.sql("""
                    WITH RECURSIVE enabled_reader(user_id) AS (
                        SELECT id FROM app_user WHERE id=:actorId AND enabled
                    ), department_tree(department_id,parent_id) AS (
                        SELECT unit.id,unit.parent_id
                        FROM enabled_reader reader
                        JOIN org_membership membership ON membership.user_id=reader.user_id
                        JOIN org_unit unit ON unit.id=membership.org_unit_id AND unit.enabled
                        UNION
                        SELECT parent.id,parent.parent_id
                        FROM department_tree child
                        JOIN org_unit parent ON parent.id=child.parent_id AND parent.enabled
                    ), effective_principal(principal_type,principal_id) AS (
                        SELECT 'USER'::text,user_id FROM enabled_reader
                        UNION SELECT 'DEPARTMENT'::text,department_id FROM department_tree
                    )
                    SELECT DISTINCT acl.space_id
                    FROM acl_entry acl
                    JOIN effective_principal principal
                      ON principal.principal_type=acl.principal_type
                     AND principal.principal_id=acl.principal_id
                    WHERE acl.scope='SPACE' AND acl.revoked_at IS NULL
                      AND (acl.expires_at IS NULL OR acl.expires_at>:at)
                    ORDER BY acl.space_id
                    """)
                    .param("actorId", actorId)
                    .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                    .query(UUID.class).list();
            return Set.copyOf(new LinkedHashSet<>(ids));
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
