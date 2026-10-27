package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.qa.QaEvidenceAuthorizationPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

public final class JdbcQaEvidenceAuthorizationAdapter implements QaEvidenceAuthorizationPort {
    private final JdbcClient jdbc;

    public JdbcQaEvidenceAuthorizationAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public boolean allAuthorized(UUID requestId, UUID actorId, Instant at) {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(at);
        try {
            return jdbc.sql("""
                    WITH RECURSIVE enabled_reader(user_id) AS (
                        SELECT id FROM app_user WHERE id=:actorId AND enabled
                    ), department_tree(department_id,parent_id) AS (
                        SELECT unit.id,unit.parent_id FROM enabled_reader reader
                        JOIN org_membership membership ON membership.user_id=reader.user_id
                        JOIN org_unit unit ON unit.id=membership.org_unit_id AND unit.enabled
                        UNION
                        SELECT parent.id,parent.parent_id FROM department_tree child
                        JOIN org_unit parent ON parent.id=child.parent_id AND parent.enabled
                    ), effective_principal(principal_type,principal_id) AS (
                        SELECT 'USER'::text,user_id FROM enabled_reader
                        UNION SELECT 'DEPARTMENT'::text,department_id FROM department_tree
                    ), requested_evidence AS (
                        SELECT snapshot.* FROM qa_evidence_snapshot snapshot
                        JOIN qa_request request ON request.id=snapshot.request_id
                            AND request.actor_id=:actorId
                        WHERE snapshot.request_id=:requestId
                    )
                    SELECT EXISTS (SELECT 1 FROM requested_evidence)
                       AND NOT EXISTS (
                           SELECT 1 FROM requested_evidence evidence
                           WHERE NOT EXISTS (
                               SELECT 1 FROM document document
                               JOIN document_version version ON version.id=evidence.version_id
                                   AND version.document_id=evidence.document_id
                                   AND version.status='PUBLISHED' AND version.parse_status='SUCCEEDED'
                               JOIN canonical_node node ON node.version_id=evidence.version_id
                                   AND node.node_id=evidence.node_id
                               WHERE document.id=evidence.document_id
                                 AND document.space_id=evidence.space_id
                                 AND NOT document.archived
                                 AND document.current_published_version_id=evidence.version_id
                           ) OR NOT EXISTS (
                               SELECT 1 FROM acl_entry acl JOIN effective_principal principal
                                 ON principal.principal_type=acl.principal_type
                                AND principal.principal_id=acl.principal_id
                               WHERE acl.space_id=evidence.space_id AND acl.scope='SPACE'
                                 AND acl.revoked_at IS NULL
                                 AND (acl.expires_at IS NULL OR acl.expires_at>:at)
                           ) OR (
                               EXISTS (
                                   SELECT 1 FROM acl_entry history
                                   WHERE history.space_id=evidence.space_id
                                     AND history.document_id=evidence.document_id
                                     AND history.scope='DOCUMENT'
                               ) AND NOT EXISTS (
                                   SELECT 1 FROM acl_entry acl JOIN effective_principal principal
                                     ON principal.principal_type=acl.principal_type
                                    AND principal.principal_id=acl.principal_id
                                   WHERE acl.space_id=evidence.space_id
                                     AND acl.document_id=evidence.document_id
                                     AND acl.scope='DOCUMENT' AND acl.revoked_at IS NULL
                                     AND (acl.expires_at IS NULL OR acl.expires_at>:at)
                               )
                           )
                       )
                    """)
                    .param("actorId", actorId)
                    .param("requestId", requestId)
                    .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                    .query(Boolean.class)
                    .single();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
