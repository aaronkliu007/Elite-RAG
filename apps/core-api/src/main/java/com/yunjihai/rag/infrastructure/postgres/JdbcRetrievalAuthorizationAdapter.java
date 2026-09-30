package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.selection.RetrievalAuthorizationPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Performs the final fail-closed retrieval authorization in one PostgreSQL round trip. */
public final class JdbcRetrievalAuthorizationAdapter implements RetrievalAuthorizationPort {
    private static final int MAX_REQUESTED_VERSIONS = 30;
    private final JdbcClient jdbc;

    public JdbcRetrievalAuthorizationAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Set<DocumentVersionRef> authorize(
            UUID userId,
            UUID spaceId,
            Set<DocumentVersionRef> requested,
            Instant checkedAt) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(requested);
        Objects.requireNonNull(checkedAt);
        if (requested.isEmpty()) return Set.of();
        if (requested.size() > MAX_REQUESTED_VERSIONS || requested.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("授权复核版本数量或内容无效");
        }

        var ordered = requested.stream()
                .sorted(Comparator.comparing(DocumentVersionRef::documentId)
                        .thenComparing(DocumentVersionRef::versionId))
                .toList();
        var inputRows = new ArrayList<String>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            inputRows.add("(CAST(:document" + index + " AS uuid), CAST(:version" + index + " AS uuid))");
        }

        String sql = """
                WITH RECURSIVE requested(document_id, version_id) AS (
                    VALUES %s
                ),
                enabled_reader(user_id) AS (
                    SELECT id FROM app_user WHERE id = :userId AND enabled
                ),
                department_tree(department_id, parent_id) AS (
                    SELECT department.id, department.parent_id
                    FROM enabled_reader reader
                    JOIN org_membership membership ON membership.user_id = reader.user_id
                    JOIN org_unit department
                      ON department.id = membership.org_unit_id AND department.enabled
                    UNION
                    SELECT parent.id, parent.parent_id
                    FROM department_tree child
                    JOIN org_unit parent ON parent.id = child.parent_id AND parent.enabled
                ),
                effective_principal(principal_type, principal_id) AS (
                    SELECT 'USER'::text, user_id FROM enabled_reader
                    UNION
                    SELECT 'DEPARTMENT'::text, department_id FROM department_tree
                )
                SELECT requested.document_id, requested.version_id
                FROM requested
                JOIN document
                  ON document.id = requested.document_id
                 AND document.space_id = :spaceId
                 AND NOT document.archived
                 AND document.current_published_version_id = requested.version_id
                JOIN document_version version
                  ON version.document_id = requested.document_id
                 AND version.id = requested.version_id
                 AND version.status = 'PUBLISHED'
                WHERE EXISTS (
                    SELECT 1
                    FROM acl_entry acl
                    JOIN effective_principal principal
                      ON principal.principal_type = acl.principal_type
                     AND principal.principal_id = acl.principal_id
                    WHERE acl.space_id = :spaceId
                      AND acl.scope = 'SPACE'
                      AND acl.revoked_at IS NULL
                      AND (acl.expires_at IS NULL OR acl.expires_at > :checkedAt)
                )
                  AND (
                    NOT EXISTS (
                        SELECT 1
                        FROM acl_entry history
                        WHERE history.space_id = :spaceId
                          AND history.document_id = requested.document_id
                          AND history.scope = 'DOCUMENT'
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM acl_entry acl
                        JOIN effective_principal principal
                          ON principal.principal_type = acl.principal_type
                         AND principal.principal_id = acl.principal_id
                        WHERE acl.space_id = :spaceId
                          AND acl.document_id = requested.document_id
                          AND acl.scope = 'DOCUMENT'
                          AND acl.revoked_at IS NULL
                          AND (acl.expires_at IS NULL OR acl.expires_at > :checkedAt)
                    )
                  )
                ORDER BY requested.document_id, requested.version_id
                """.formatted(String.join(", ", inputRows));

        try {
            var statement = jdbc.sql(sql)
                    .param("userId", userId)
                    .param("spaceId", spaceId)
                    .param("checkedAt", OffsetDateTime.ofInstant(checkedAt, ZoneOffset.UTC));
            for (int index = 0; index < ordered.size(); index++) {
                var reference = ordered.get(index);
                statement = statement
                        .param("document" + index, reference.documentId())
                        .param("version" + index, reference.versionId());
            }
            return Set.copyOf(statement.query((row, number) -> new DocumentVersionRef(
                    row.getObject("document_id", UUID.class),
                    row.getObject("version_id", UUID.class))).list());
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
