package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.PermissionGrantQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class JdbcPermissionGrantQuery implements PermissionGrantQuery {
    private final JdbcClient jdbc;
    public JdbcPermissionGrantQuery(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override
    public List<Grant> listForSpace(UUID spaceId, int offset, int limit) {
        return jdbc.sql("""
                SELECT a.id, a.principal_type, a.principal_id, c.username_normalized,
                       COALESCE(u.display_name, department.name) AS display_name, a.grant_source, a.expires_at
                FROM acl_entry a
                LEFT JOIN app_user u ON a.principal_type = 'USER' AND u.id = a.principal_id
                LEFT JOIN local_credential c ON c.user_id = u.id
                LEFT JOIN org_unit department ON a.principal_type = 'DEPARTMENT' AND department.id = a.principal_id
                WHERE a.scope = 'SPACE' AND a.space_id = :space AND a.revoked_at IS NULL
                ORDER BY a.principal_type, a.principal_id, a.grant_source, a.id
                LIMIT :limit OFFSET :offset
                """).param("space", spaceId).param("limit", limit).param("offset", offset)
                .query((row, number) -> new Grant(row.getObject("id", UUID.class), row.getString("principal_type"),
                        row.getObject("principal_id", UUID.class), row.getString("username_normalized"),
                        row.getString("display_name"), row.getString("grant_source"),
                        row.getObject("expires_at", OffsetDateTime.class))).list();
    }
}
