package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.AccessDecisionRepository;
import com.yunjihai.rag.access.AccessSnapshot;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PublicationState;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Produces one ACL snapshot from current membership, ACL, publication, archive and expiry facts. */
public final class JdbcAccessDecisionRepository implements AccessDecisionRepository {
    private final JdbcClient jdbc;

    public JdbcAccessDecisionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<AccessSnapshot> findReadDecision(
            UUID spaceId,
            UUID documentId,
            Set<PrincipalRef> principals,
            Instant at) {
        Objects.requireNonNull(spaceId, "spaceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(principals, "principals must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (principals.isEmpty()) {
            return Optional.empty();
        }

        var orderedPrincipals = principals.stream()
                .sorted(Comparator.comparing((PrincipalRef principal) -> principal.type().name())
                        .thenComparing(PrincipalRef::id))
                .toList();
        var values = new ArrayList<String>(orderedPrincipals.size());
        for (int index = 0; index < orderedPrincipals.size(); index++) {
            values.add("(CAST(:principalType" + index + " AS text), CAST(:principalId" + index + " AS uuid))");
        }

        String sql = """
                WITH RECURSIVE input_principal(principal_type, principal_id) AS (
                    VALUES %s
                ),
                current_department(user_id, department_id, parent_id) AS (
                    SELECT input.principal_id,
                           department.id,
                           department.parent_id
                    FROM input_principal input
                    JOIN app_user app_user
                      ON input.principal_type = 'USER'
                     AND app_user.id = input.principal_id
                     AND app_user.enabled
                    JOIN org_membership membership ON membership.user_id = app_user.id
                    JOIN org_unit department
                      ON department.id = membership.org_unit_id
                     AND department.enabled
                    UNION
                    SELECT tree.user_id,
                           parent.id,
                           parent.parent_id
                    FROM current_department tree
                    JOIN org_unit parent
                      ON parent.id = tree.parent_id
                     AND parent.enabled
                ),
                effective_principal(principal_type, principal_id) AS (
                    SELECT input.principal_type, input.principal_id
                    FROM input_principal input
                    JOIN app_user app_user
                      ON input.principal_type = 'USER'
                     AND app_user.id = input.principal_id
                     AND app_user.enabled
                    UNION
                    SELECT input.principal_type, input.principal_id
                    FROM input_principal input
                    JOIN current_department department
                      ON input.principal_type = 'DEPARTMENT'
                     AND department.department_id = input.principal_id
                ),
                matching_acl AS (
                    SELECT acl.scope, acl.document_id, acl.expires_at
                    FROM acl_entry acl
                    JOIN effective_principal principal
                      ON principal.principal_type = acl.principal_type
                     AND principal.principal_id = acl.principal_id
                    WHERE acl.space_id = :spaceId
                      AND acl.revoked_at IS NULL
                      AND (acl.expires_at IS NULL OR acl.expires_at > :at)
                ),
                space_match AS (
                    SELECT count(*) > 0 AS granted,
                           coalesce(bool_or(expires_at IS NULL), false) AS permanent,
                           max(expires_at) AS valid_until
                    FROM matching_acl
                    WHERE scope = 'SPACE'
                ),
                document_match AS (
                    SELECT count(*) > 0 AS granted,
                           coalesce(bool_or(expires_at IS NULL), false) AS permanent,
                           max(expires_at) AS valid_until
                    FROM matching_acl
                    WHERE scope = 'DOCUMENT' AND document_id = :documentId
                ),
                document_restriction AS (
                    SELECT EXISTS (
                        SELECT 1
                        FROM acl_entry
                        WHERE space_id = :spaceId
                          AND document_id = :documentId
                          AND scope = 'DOCUMENT'
                    ) AS restricted
                )
                SELECT space_match.granted AS space_granted,
                       document_restriction.restricted AS document_restricted,
                       document_match.granted AS document_granted,
                       CASE
                           WHEN document.archived THEN 'ARCHIVED'
                           WHEN published.status IS NULL THEN 'DRAFT'
                           ELSE published.status
                       END AS publication_state,
                       CASE
                           WHEN NOT space_match.granted THEN NULL
                           WHEN document_restriction.restricted AND NOT document_match.granted THEN NULL
                           WHEN NOT document_restriction.restricted THEN
                               CASE WHEN space_match.permanent THEN NULL ELSE space_match.valid_until END
                           WHEN space_match.permanent AND document_match.permanent THEN NULL
                           WHEN space_match.permanent THEN document_match.valid_until
                           WHEN document_match.permanent THEN space_match.valid_until
                           ELSE least(space_match.valid_until, document_match.valid_until)
                       END AS valid_until
                FROM document
                LEFT JOIN document_version published
                  ON published.document_id = document.id
                 AND published.id = document.current_published_version_id
                CROSS JOIN space_match
                CROSS JOIN document_match
                CROSS JOIN document_restriction
                WHERE document.id = :documentId
                  AND document.space_id = :spaceId
                """.formatted(String.join(", ", values));

        try {
            var statement = jdbc.sql(sql)
                    .param("spaceId", spaceId)
                    .param("documentId", documentId)
                    .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
            for (int index = 0; index < orderedPrincipals.size(); index++) {
                PrincipalRef principal = orderedPrincipals.get(index);
                statement = statement
                        .param("principalType" + index, principal.type().name())
                        .param("principalId" + index, principal.id());
            }
            return statement.query((resultSet, rowNumber) -> {
                OffsetDateTime validUntil = resultSet.getObject("valid_until", OffsetDateTime.class);
                return new AccessSnapshot(
                        resultSet.getBoolean("space_granted"),
                        resultSet.getBoolean("document_restricted"),
                        resultSet.getBoolean("document_granted"),
                        PublicationState.valueOf(resultSet.getString("publication_state")),
                        validUntil == null ? null : validUntil.toInstant());
            }).optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
