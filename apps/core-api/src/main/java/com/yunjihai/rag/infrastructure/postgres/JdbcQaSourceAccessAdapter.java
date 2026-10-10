package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.qa.QaSourceAccessPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcQaSourceAccessAdapter implements QaSourceAccessPort {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcQaSourceAccessAdapter(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public Optional<QaSource> findAuthorized(
            UUID requestId, UUID evidenceId, UUID actorId, Instant at) {
        Objects.requireNonNull(requestId); Objects.requireNonNull(evidenceId);
        Objects.requireNonNull(actorId); Objects.requireNonNull(at);
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
                    )
                    SELECT snapshot.request_id,snapshot.evidence_id,snapshot.space_id,
                           snapshot.document_id,snapshot.version_id,snapshot.node_id,snapshot.title,
                           snapshot.locator_type,snapshot.locator::text
                    FROM qa_evidence_snapshot snapshot
                    JOIN qa_request request ON request.id=snapshot.request_id AND request.actor_id=:actorId
                    JOIN qa_answer answer ON answer.request_id=request.id
                        AND snapshot.evidence_id=ANY(answer.cited_evidence_ids)
                    JOIN document document ON document.id=snapshot.document_id
                        AND document.space_id=snapshot.space_id AND NOT document.archived
                        AND document.current_published_version_id=snapshot.version_id
                    JOIN document_version version ON version.id=snapshot.version_id
                        AND version.document_id=snapshot.document_id
                        AND version.status='PUBLISHED' AND version.parse_status='SUCCEEDED'
                    JOIN canonical_node node ON node.version_id=snapshot.version_id
                        AND node.node_id=snapshot.node_id
                    WHERE snapshot.request_id=:requestId AND snapshot.evidence_id=:evidenceId
                      AND EXISTS (
                        SELECT 1 FROM acl_entry acl JOIN effective_principal principal
                          ON principal.principal_type=acl.principal_type
                         AND principal.principal_id=acl.principal_id
                        WHERE acl.space_id=snapshot.space_id AND acl.scope='SPACE'
                          AND acl.revoked_at IS NULL
                          AND (acl.expires_at IS NULL OR acl.expires_at>:at))
                      AND (NOT EXISTS (
                        SELECT 1 FROM acl_entry history
                        WHERE history.space_id=snapshot.space_id
                          AND history.document_id=snapshot.document_id AND history.scope='DOCUMENT')
                       OR EXISTS (
                        SELECT 1 FROM acl_entry acl JOIN effective_principal principal
                          ON principal.principal_type=acl.principal_type
                         AND principal.principal_id=acl.principal_id
                        WHERE acl.space_id=snapshot.space_id
                          AND acl.document_id=snapshot.document_id AND acl.scope='DOCUMENT'
                          AND acl.revoked_at IS NULL
                          AND (acl.expires_at IS NULL OR acl.expires_at>:at)))
                    """)
                    .param("actorId", actorId).param("requestId", requestId)
                    .param("evidenceId", evidenceId)
                    .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                    .query((row, number) -> new QaSource(
                            row.getObject("request_id", UUID.class),
                            row.getObject("evidence_id", UUID.class),
                            row.getObject("space_id", UUID.class),
                            row.getObject("document_id", UUID.class),
                            row.getObject("version_id", UUID.class), row.getString("node_id"),
                            row.getString("title"), QaLocatorJson.read(json,
                                    row.getString("locator_type"), row.getString("locator"))))
                    .optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
