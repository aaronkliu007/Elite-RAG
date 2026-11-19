package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.qa.QaPersistencePort;
import com.yunjihai.rag.qa.domain.*;
import com.yunjihai.rag.retrieval.domain.Evidence;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class JdbcQaPersistenceAdapter implements QaPersistencePort {
    private static final int CLAIMS_SCHEMA_VERSION = 1;
    private static final TypeReference<List<CitationClaim>> CLAIMS = new TypeReference<>() { };
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final JdbcProjectionDocumentLock documentLock;

    public JdbcQaPersistenceAdapter(
            JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.json = Objects.requireNonNull(json);
        this.documentLock = new JdbcProjectionDocumentLock(jdbc);
    }

    @Override
    public RequestState create(UUID requestId, UUID actorId, String questionDigest, Instant at) {
        Objects.requireNonNull(requestId); Objects.requireNonNull(actorId);
        Objects.requireNonNull(questionDigest); Objects.requireNonNull(at);
        try {
            jdbc.sql("""
                    INSERT INTO qa_request(
                        id,actor_id,question_digest,status,revision,degraded,created_at,updated_at)
                    VALUES (:id,:actorId,:digest,'RECEIVED',0,false,:at,:at)
                    ON CONFLICT (id) DO NOTHING
                    """).param("id", requestId).param("actorId", actorId)
                    .param("digest", questionDigest).param("at", utc(at)).update();
            var row = jdbc.sql("""
                    SELECT id,actor_id,question_digest,status,revision,degraded
                    FROM qa_request WHERE id=:id
                    """).param("id", requestId).query((rs, number) -> new CreatedRequest(
                            state(rs, number), rs.getString("question_digest"))).single();
            if (!row.state().actorId().equals(actorId) || !row.digest().equals(questionDigest)) {
                throw new IllegalStateException("QA_REQUEST_CONFLICT");
            }
            return row.state();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<RequestState> transition(
            UUID requestId, long expectedRevision, QaStatus next, Instant at) {
        Objects.requireNonNull(next); Objects.requireNonNull(at);
        Optional<RequestState> current = load(requestId);
        if (current.isEmpty() || current.orElseThrow().revision() != expectedRevision) return Optional.empty();
        RequestState state = current.orElseThrow();
        state.status().advanceTo(next);
        try {
            return jdbc.sql("""
                    UPDATE qa_request SET status=:next,revision=revision+1,updated_at=:at
                    WHERE id=:id AND revision=:revision AND status=:current
                    RETURNING id,actor_id,status,revision,degraded
                    """).param("next", next.name()).param("at", utc(at)).param("id", requestId)
                    .param("revision", expectedRevision).param("current", state.status().name())
                    .query(JdbcQaPersistenceAdapter::state).optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<RequestState> saveEvidence(
            UUID requestId, long expectedRevision, EvidenceBundle evidence,
            boolean degraded, Instant at) {
        Objects.requireNonNull(evidence); Objects.requireNonNull(at);
        try {
            return Optional.ofNullable(transactions.execute(transaction -> {
                RequestState current = load(requestId).orElseThrow(StaleRevision::new);
                if (current.revision() != expectedRevision || current.status() != QaStatus.RETRIEVING) {
                    throw new StaleRevision();
                }
                int updated = jdbc.sql("""
                        UPDATE qa_request
                        SET status='SANITIZING',revision=revision+1,degraded=:degraded,updated_at=:at
                        WHERE id=:id AND revision=:revision AND status='RETRIEVING'
                        """).param("degraded", degraded).param("at", utc(at)).param("id", requestId)
                        .param("revision", expectedRevision).update();
                if (updated != 1) throw new StaleRevision();
                int ordinal = 0;
                for (QaEvidence item : evidence.evidence()) {
                    jdbc.sql("""
                            INSERT INTO qa_evidence_snapshot(
                                request_id,evidence_id,ordinal,space_id,document_id,version_id,
                                node_id,content_digest,title,score,locator_type,locator)
                            VALUES (:requestId,:evidenceId,:ordinal,:spaceId,:documentId,:versionId,
                                :nodeId,:contentDigest,:title,:score,:locatorType,CAST(:locator AS jsonb))
                            """).param("requestId", requestId).param("evidenceId", item.evidenceId())
                            .param("ordinal", ordinal++).param("spaceId", item.spaceId())
                            .param("documentId", item.documentId()).param("versionId", item.versionId())
                            .param("nodeId", item.nodeId()).param("contentDigest", sha256(item.excerpt()))
                            .param("title", item.title())
                            .param("score", item.score()).param("locatorType", QaLocatorJson.type(item.locator()))
                            .param("locator", QaLocatorJson.write(json, item.locator())).update();
                }
                return load(requestId).orElseThrow(StaleRevision::new);
            }));
        } catch (StaleRevision stale) {
            return Optional.empty();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public boolean saveConflictScreening(UUID requestId, long expectedRevision, List<Evidence> candidates) {
        Objects.requireNonNull(requestId);
        var items = List.copyOf(candidates);
        if (items.isEmpty() || items.size() > 128) return false;
        try {
            return Boolean.TRUE.equals(transactions.execute(transaction -> {
                var state = jdbc.sql("SELECT status,revision FROM qa_request WHERE id=:id FOR UPDATE")
                        .param("id", requestId).query((rs, row) ->
                                rs.getString("status").equals("SANITIZING") && rs.getLong("revision") == expectedRevision)
                        .optional();
                if (state.isEmpty() || !state.orElseThrow()) return false;
                if (jdbc.sql("SELECT count(*) FROM qa_conflict_screening_snapshot WHERE request_id=:id")
                        .param("id", requestId).query(Long.class).single() != 0) return false;
                for (Evidence item : items) {
                    jdbc.sql("""
                            INSERT INTO qa_conflict_screening_snapshot(request_id,space_id,document_id,version_id,node_id,context_fingerprint)
                            VALUES (:request,:space,:document,:version,:node,qa_document_context_fingerprint(:document,:version)) ON CONFLICT DO NOTHING
                            """).param("request", requestId).param("space", item.spaceId())
                            .param("document", item.documentId()).param("version", item.versionId())
                            .param("node", item.nodeId()).update();
                }
                return true;
            }));
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<RequestState> complete(
            UUID requestId, long expectedRevision, QaOutcome outcome, Instant at) {
        Objects.requireNonNull(outcome); Objects.requireNonNull(at);
        if (!requestId.equals(outcome.requestId())) throw new IllegalArgumentException("request id mismatch");
        try {
            return Optional.ofNullable(transactions.execute(transaction -> {
                RequestState current = load(requestId).orElseThrow(StaleRevision::new);
                if (current.revision() != expectedRevision) throw new StaleRevision();
                current.status().advanceTo(outcome.status());
                String refusal = outcome instanceof QaOutcome.Refused refused ? refused.reason().name() : null;
                String failure = outcome instanceof QaOutcome.Failed failed ? failed.code() : null;
                boolean degraded = switch (outcome) {
                    case QaOutcome.Answered answered -> answered.degraded();
                    case QaOutcome.Refused refused -> refused.degraded();
                    case QaOutcome.Failed failed -> failed.degraded();
                };
                int updated = jdbc.sql("""
                        UPDATE qa_request SET status=:status,revision=revision+1,degraded=:degraded,
                            refusal_reason=:refusal,failure_code=:failure,completed_at=:at,updated_at=:at
                        WHERE id=:id AND revision=:revision AND status=:current
                        """).param("status", outcome.status().name()).param("degraded", degraded)
                        .param("refusal", refusal, Types.VARCHAR).param("failure", failure, Types.VARCHAR)
                        .param("at", utc(at)).param("id", requestId).param("revision", expectedRevision)
                        .param("current", current.status().name()).update();
                if (updated != 1) throw new StaleRevision();
                if (outcome instanceof QaOutcome.Answered answered) {
                    List<UUID> citedEvidenceIds = answered.claims().stream()
                            .flatMap(claim -> claim.evidenceIds().stream()).distinct().toList();
                    verifyCitationsBelongToRequest(requestId, citedEvidenceIds);
                    jdbc.sql("""
                            INSERT INTO qa_answer(
                                request_id,answer_text,claims_schema_version,claims,cited_evidence_ids,created_at)
                            VALUES (:requestId,:answer,:schemaVersion,CAST(:claims AS jsonb),
                                CAST(:citedEvidenceIds AS uuid[]),:at)
                            """).param("requestId", requestId).param("answer", answered.answer())
                            .param("schemaVersion", CLAIMS_SCHEMA_VERSION)
                            .param("claims", writeClaims(answered.claims()))
                            .param("citedEvidenceIds", uuidArray(citedEvidenceIds))
                            .param("at", utc(at)).update();
                }
                return load(requestId).orElseThrow(StaleRevision::new);
            }));
        } catch (StaleRevision stale) {
            return Optional.empty();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public AuthorizedCompletion completeAuthorized(
            UUID requestId, long expectedRevision, QaOutcome.Answered outcome, Instant at,
            BooleanSupplier authorizationCheck) {
        Objects.requireNonNull(requestId); Objects.requireNonNull(outcome);
        Objects.requireNonNull(at); Objects.requireNonNull(authorizationCheck);
        if (!requestId.equals(outcome.requestId())) throw new IllegalArgumentException("request id mismatch");
        try {
            return transactions.execute(transaction -> {
                lockActorAndSpaces(requestId);
                List<UUID> documents = jdbc.sql("""
                        SELECT document_id FROM qa_evidence_snapshot WHERE request_id=:requestId
                        UNION SELECT document_id FROM qa_conflict_screening_snapshot WHERE request_id=:requestId
                        ORDER BY document_id
                        """).param("requestId", requestId).query(UUID.class).list();
                return withDocumentLocks(documents, 0, () -> {
                    lockScreeningContent(requestId);
                    if (!authorizationCheck.getAsBoolean()) {
                        return new AuthorizedCompletion(false, Optional.empty());
                    }
                    return new AuthorizedCompletion(true,
                            complete(requestId, expectedRevision, outcome, at));
                });
            });
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private void lockActorAndSpaces(UUID requestId) {
        jdbc.sql("""
                SELECT actor.id FROM app_user actor JOIN qa_request request ON request.actor_id=actor.id
                WHERE request.id=:requestId FOR SHARE OF actor
                """).param("requestId", requestId).query(UUID.class).single();
        jdbc.sql("""
                SELECT space.id FROM knowledge_space space
                WHERE space.id IN (SELECT space_id FROM qa_evidence_snapshot WHERE request_id=:requestId
                                   UNION SELECT space_id FROM qa_conflict_screening_snapshot WHERE request_id=:requestId)
                ORDER BY space.id FOR SHARE
                """).param("requestId", requestId).query(UUID.class).list();
    }

    private <T> T withDocumentLocks(List<UUID> documents, int index, Supplier<T> action) {
        if (index == documents.size()) return action.get();
        return documentLock.withDocumentLock(documents.get(index),
                () -> withDocumentLocks(documents, index + 1, action));
    }

    private void lockScreeningContent(UUID requestId) {
        jdbc.sql("""
                SELECT d.id FROM document d WHERE d.id IN (
                    SELECT document_id FROM qa_evidence_snapshot WHERE request_id=:id
                    UNION SELECT document_id FROM qa_conflict_screening_snapshot WHERE request_id=:id)
                ORDER BY d.id FOR SHARE OF d
                """).param("id",requestId).query(UUID.class).list();
        jdbc.sql("""
                SELECT v.id FROM document_version v WHERE v.id IN (
                    SELECT version_id FROM qa_evidence_snapshot WHERE request_id=:id
                    UNION SELECT version_id FROM qa_conflict_screening_snapshot WHERE request_id=:id)
                ORDER BY v.id FOR SHARE OF v
                """).param("id",requestId).query(UUID.class).list();
        jdbc.sql("""
                SELECT c.version_id FROM canonical_document c
                WHERE c.version_id IN (SELECT version_id FROM qa_conflict_screening_snapshot WHERE request_id=:id)
                ORDER BY c.version_id FOR UPDATE OF c
                """).param("id",requestId).query(UUID.class).list();
        jdbc.sql("""
                SELECT n.node_id FROM canonical_node n
                WHERE n.version_id IN (SELECT version_id FROM qa_conflict_screening_snapshot WHERE request_id=:id)
                ORDER BY n.version_id,n.node_id FOR SHARE OF n
                """).param("id",requestId).query(String.class).list();
    }

    @Override
    public Optional<QaOutcome> findAuthorized(UUID requestId, UUID actorId, BooleanSupplier authorizationCheck) {
        Objects.requireNonNull(authorizationCheck);
        try {
            return transactions.execute(transaction -> {
                var initial = find(requestId, actorId);
                if (initial.isEmpty() || !(initial.orElseThrow() instanceof QaOutcome.Answered)) return initial;
                lockActorAndSpaces(requestId);
                List<UUID> documents = jdbc.sql("""
                        SELECT document_id FROM qa_evidence_snapshot WHERE request_id=:requestId
                        UNION SELECT document_id FROM qa_conflict_screening_snapshot WHERE request_id=:requestId
                        ORDER BY document_id
                        """).param("requestId", requestId).query(UUID.class).list();
                return withDocumentLocks(documents, 0, () -> {
                    lockScreeningContent(requestId);
                    return authorizationCheck.getAsBoolean() ? find(requestId, actorId) : Optional.empty();
                });
            });
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<String> answerPolicyVersion(UUID requestId, UUID actorId) {
        Objects.requireNonNull(requestId); Objects.requireNonNull(actorId);
        try {
            return jdbc.sql("""
                    SELECT audit.qa_policy_version FROM qa_model_call_audit audit
                    JOIN qa_request request ON request.id=audit.request_id
                    WHERE request.id=:id AND request.actor_id=:actor AND request.status='ANSWERED'
                    """).param("id",requestId).param("actor",actorId).query(String.class).optional();
        } catch(DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<QaOutcome> find(UUID requestId, UUID actorId) {
        Objects.requireNonNull(requestId); Objects.requireNonNull(actorId);
        try {
            return jdbc.sql("""
                    SELECT request.status,request.degraded,request.refusal_reason,request.failure_code,
                           answer.answer_text,answer.claims_schema_version,answer.claims::text
                    FROM qa_request request LEFT JOIN qa_answer answer ON answer.request_id=request.id
                    WHERE request.id=:id AND request.actor_id=:actorId
                    """).param("id", requestId).param("actorId", actorId)
                    .query((row, number) -> mapOutcome(requestId, row.getString("status"),
                            row.getBoolean("degraded"), row.getString("refusal_reason"),
                            row.getString("failure_code"), row.getString("answer_text"),
                            row.getObject("claims_schema_version", Integer.class),
                            row.getString("claims"))).optional().flatMap(value -> value);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<Instant> completedAt(UUID requestId, UUID actorId) {
        Objects.requireNonNull(requestId); Objects.requireNonNull(actorId);
        try {
            return jdbc.sql("""
                    SELECT completed_at FROM qa_request
                    WHERE id=:id AND actor_id=:actorId
                      AND status IN ('ANSWERED','REFUSED','FAILED')
                    """).param("id", requestId).param("actorId", actorId)
                    .query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private Optional<RequestState> load(UUID requestId) {
        return jdbc.sql("SELECT id,actor_id,status,revision,degraded FROM qa_request WHERE id=:id")
                .param("id", requestId).query(JdbcQaPersistenceAdapter::state).optional();
    }

    private Optional<QaOutcome> mapOutcome(
            UUID requestId, String status, boolean degraded, String refusal,
            String failure, String answer, Integer claimsSchemaVersion, String claims) {
        return switch (QaStatus.valueOf(status)) {
            case ANSWERED -> {
                if (!Objects.equals(claimsSchemaVersion, CLAIMS_SCHEMA_VERSION)) {
                    throw new IllegalStateException("QA_CLAIMS_SCHEMA_UNSUPPORTED");
                }
                yield Optional.of(new QaOutcome.Answered(
                        requestId, answer, readClaims(claims), degraded));
            }
            case REFUSED -> Optional.of(new QaOutcome.Refused(
                    requestId, RefusalReason.valueOf(refusal), degraded));
            case FAILED -> Optional.of(new QaOutcome.Failed(requestId, failure, degraded));
            default -> Optional.empty();
        };
    }

    private String writeClaims(List<CitationClaim> claims) {
        try { return json.writeValueAsString(claims); }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("QA_CLAIMS_SERIALIZATION_FAILED", failure);
        }
    }

    private List<CitationClaim> readClaims(String claims) {
        try { return json.readValue(claims, CLAIMS); }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("QA_CLAIMS_DESERIALIZATION_FAILED", failure);
        }
    }

    private static RequestState state(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new RequestState(row.getObject("id", UUID.class), row.getObject("actor_id", UUID.class),
                QaStatus.valueOf(row.getString("status")), row.getLong("revision"),
                row.getBoolean("degraded"));
    }

    private static OffsetDateTime utc(Instant at) { return OffsetDateTime.ofInstant(at, ZoneOffset.UTC); }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String uuidArray(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private void verifyCitationsBelongToRequest(UUID requestId, List<UUID> evidenceIds) {
        long stored = jdbc.sql("""
                SELECT count(*) FROM qa_evidence_snapshot
                WHERE request_id=:requestId AND evidence_id=ANY(CAST(:evidenceIds AS uuid[]))
                """).param("requestId", requestId).param("evidenceIds", uuidArray(evidenceIds))
                .query(Long.class).single();
        if (stored != evidenceIds.size()) {
            throw new IllegalArgumentException("QA_ANSWER_REFERENCES_UNKNOWN_EVIDENCE");
        }
    }
    private record CreatedRequest(RequestState state, String digest) { }
    private static final class StaleRevision extends RuntimeException { }
}
