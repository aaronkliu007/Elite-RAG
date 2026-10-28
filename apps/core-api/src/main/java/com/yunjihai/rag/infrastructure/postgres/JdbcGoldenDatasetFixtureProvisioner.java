package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.qa.evaluation.EvaluationCase;
import com.yunjihai.rag.qa.evaluation.EvaluationDataset;
import com.yunjihai.rag.retrieval.projection.ProjectionEventRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Installs the fictional, fixed-identity knowledge needed by the bundled golden QA dataset.
 * The provisioner is deliberately opt-in at configuration level and fails closed when any
 * reserved identity is already occupied by non-fixture data.
 */
public final class JdbcGoldenDatasetFixtureProvisioner implements ApplicationRunner {
    static final UUID EVALUATION_ACTOR = UUID.fromString("00000000-0000-7000-8000-000000000100");
    static final UUID ACCESSIBLE_SPACE = UUID.fromString("10000000-0000-7000-8000-000000000100");
    static final UUID RESTRICTED_SPACE = UUID.fromString("10000000-0000-7000-8000-000000000101");

    private static final String ACTOR_EXTERNAL_ID = "qa-evaluation-fixture:golden-v1";
    private static final String ACTOR_DISPLAY_NAME = "黄金评测集虚构用户";
    private static final String ACCESSIBLE_SPACE_NAME = "黄金评测集（已授权虚构知识）";
    private static final String RESTRICTED_SPACE_NAME = "黄金评测集（越权陷阱虚构知识）";
    private static final long ADVISORY_LOCK_KEY = 0x454c495445524147L;
    private static final UUID ACCESSIBLE_SPACE_ACL = deterministicId("accessible-space-acl");
    private static final String CONFLICT_CODE = "EVALUATION_FIXTURE_ID_CONFLICT";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ProjectionEventRepository projectionEvents;
    private final EvaluationDataset dataset;
    private final Clock clock;

    public JdbcGoldenDatasetFixtureProvisioner(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            ProjectionEventRepository projectionEvents,
            EvaluationDataset dataset,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.projectionEvents = Objects.requireNonNull(projectionEvents);
        this.dataset = Objects.requireNonNull(dataset);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        provision();
    }

    public ProvisioningResult provision() {
        var documents = desiredDocuments();
        Boolean installed = transactions.execute(status -> provisionWithinTransaction(documents));
        if (installed == null) throw new IllegalStateException(CONFLICT_CODE);
        int accessible = Math.toIntExact(documents.stream()
                .filter(document -> document.spaceId().equals(ACCESSIBLE_SPACE)).count());
        int restricted = documents.size() - accessible;
        return new ProvisioningResult(installed, accessible, restricted);
    }

    private boolean provisionWithinTransaction(List<DesiredDocument> documents) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", ADVISORY_LOCK_KEY)
                .query((row, rowNumber) -> rowNumber)
                .single();

        if (reservedIdentityExists(documents)) {
            if (!fixtureMatches(documents)) throw conflict();
            return false;
        }

        Instant now = clock.instant();
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        insertActor(at);
        insertSpaces(at);
        insertAccessibleSpaceAcl(at);
        for (var document : documents) insertDocument(document, at);
        projectionEvents.enqueueSpace(ACCESSIBLE_SPACE, now);
        projectionEvents.enqueueSpace(RESTRICTED_SPACE, now);
        return true;
    }

    private boolean reservedIdentityExists(List<DesiredDocument> documents) {
        if (jdbc.sql("SELECT count(*) FROM app_user WHERE id=:id OR external_id=:externalId")
                .param("id", EVALUATION_ACTOR)
                .param("externalId", ACTOR_EXTERNAL_ID)
                .query(Long.class).single() > 0) return true;
        if (exists("knowledge_space", ACCESSIBLE_SPACE) || exists("knowledge_space", RESTRICTED_SPACE)) {
            return true;
        }
        if (exists("acl_entry", ACCESSIBLE_SPACE_ACL)) return true;
        for (var document : documents) {
            if (exists("document", document.documentId())
                    || exists("document_version", document.versionId())) return true;
        }
        return false;
    }

    private boolean fixtureMatches(List<DesiredDocument> documents) {
        if (!jdbc.sql("""
                SELECT count(*)=1 FROM app_user
                WHERE id=:id AND external_id=:externalId AND display_name=:displayName AND enabled
                """)
                .param("id", EVALUATION_ACTOR)
                .param("externalId", ACTOR_EXTERNAL_ID)
                .param("displayName", ACTOR_DISPLAY_NAME)
                .query(Boolean.class).single()) return false;
        if (!spaceMatches(ACCESSIBLE_SPACE, ACCESSIBLE_SPACE_NAME)
                || !spaceMatches(RESTRICTED_SPACE, RESTRICTED_SPACE_NAME)) return false;
        if (!jdbc.sql("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM acl_entry
                    WHERE space_id=:spaceId AND scope='SPACE'
                      AND revoked_at IS NULL
                )
                """)
                .param("spaceId", RESTRICTED_SPACE)
                .query(Boolean.class).single()) return false;
        if (!jdbc.sql("""
                SELECT count(*)=1 FROM acl_entry
                WHERE id=:id AND space_id=:spaceId AND document_id IS NULL AND scope='SPACE'
                  AND principal_type='USER' AND principal_id=:actorId
                  AND expires_at IS NULL AND revoked_at IS NULL
                  AND (SELECT count(*) FROM acl_entry active_acl
                       WHERE active_acl.space_id=:spaceId AND active_acl.scope='SPACE'
                         AND active_acl.revoked_at IS NULL)=1
                """)
                .param("id", ACCESSIBLE_SPACE_ACL)
                .param("spaceId", ACCESSIBLE_SPACE)
                .param("actorId", EVALUATION_ACTOR)
                .query(Boolean.class).single()) return false;
        if (!hasProjectionEvent(ACCESSIBLE_SPACE) || !hasProjectionEvent(RESTRICTED_SPACE)) {
            return false;
        }
        for (var document : documents) {
            if (!documentMatches(document)) return false;
        }
        return true;
    }

    private boolean hasProjectionEvent(UUID spaceId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM retrieval_projection_event
                    WHERE scope_type='SPACE' AND space_id=:spaceId
                )
                """)
                .param("spaceId", spaceId)
                .query(Boolean.class).single();
    }

    private boolean spaceMatches(UUID spaceId, String name) {
        return jdbc.sql("""
                SELECT count(*)=1 FROM knowledge_space
                WHERE id=:id AND name=:name AND created_by=:actorId
                """)
                .param("id", spaceId)
                .param("name", name)
                .param("actorId", EVALUATION_ACTOR)
                .query(Boolean.class).single();
    }

    private boolean documentMatches(DesiredDocument document) {
        return jdbc.sql("""
                SELECT count(*)=1
                FROM document d
                JOIN document_version v ON v.id=d.current_published_version_id
                                       AND v.document_id=d.id
                JOIN canonical_document c ON c.version_id=v.id
                JOIN canonical_node n ON n.version_id=v.id
                WHERE d.id=:documentId AND d.space_id=:spaceId AND d.title=:title
                  AND NOT d.archived AND d.revision=1
                  AND v.id=:versionId AND v.version_number=1
                  AND v.source_blob_id=:sourceBlobId AND v.status='PUBLISHED'
                  AND v.parse_status='SUCCEEDED' AND v.created_by=:actorId
                  AND v.status_changed_by=:actorId AND v.status_changed_at IS NOT NULL
                  AND c.source_filename=:sourceFilename AND c.media_type='text/markdown'
                  AND c.page_count=1
                  AND n.node_id=:nodeId AND n.kind='PARAGRAPH' AND n.body_text=:body
                  AND n.parent_node_id IS NULL AND n.page IS NULL
                  AND n.bounding_box IS NULL AND n.table_cells IS NULL
                  AND n.confidence=1.0 AND n.stable_order=0
                  AND (SELECT count(*) FROM canonical_node only_node
                       WHERE only_node.version_id=v.id)=1
                """)
                .param("documentId", document.documentId())
                .param("spaceId", document.spaceId())
                .param("title", document.title())
                .param("versionId", document.versionId())
                .param("sourceBlobId", document.sourceBlobId())
                .param("actorId", EVALUATION_ACTOR)
                .param("sourceFilename", document.sourceFilename())
                .param("nodeId", document.nodeId())
                .param("body", document.body())
                .query(Boolean.class).single();
    }

    private void insertActor(OffsetDateTime at) {
        jdbc.sql("""
                INSERT INTO app_user(id,external_id,display_name,enabled,created_at,updated_at)
                VALUES (:id,:externalId,:displayName,true,:at,:at)
                """)
                .param("id", EVALUATION_ACTOR)
                .param("externalId", ACTOR_EXTERNAL_ID)
                .param("displayName", ACTOR_DISPLAY_NAME)
                .param("at", at)
                .update();
    }

    private void insertSpaces(OffsetDateTime at) {
        insertSpace(ACCESSIBLE_SPACE, ACCESSIBLE_SPACE_NAME, at);
        insertSpace(RESTRICTED_SPACE, RESTRICTED_SPACE_NAME, at);
    }

    private void insertSpace(UUID id, String name, OffsetDateTime at) {
        jdbc.sql("""
                INSERT INTO knowledge_space(id,name,created_by,created_at)
                VALUES (:id,:name,:actorId,:at)
                """)
                .param("id", id)
                .param("name", name)
                .param("actorId", EVALUATION_ACTOR)
                .param("at", at)
                .update();
    }

    private void insertAccessibleSpaceAcl(OffsetDateTime at) {
        jdbc.sql("""
                INSERT INTO acl_entry(
                    id,space_id,document_id,scope,principal_type,principal_id,
                    expires_at,revoked_at,created_at,updated_at)
                VALUES (:id,:spaceId,NULL,'SPACE','USER',:actorId,NULL,NULL,:at,:at)
                """)
                .param("id", ACCESSIBLE_SPACE_ACL)
                .param("spaceId", ACCESSIBLE_SPACE)
                .param("actorId", EVALUATION_ACTOR)
                .param("at", at)
                .update();
    }

    private void insertDocument(DesiredDocument document, OffsetDateTime at) {
        jdbc.sql("""
                INSERT INTO document(id,space_id,title,current_published_version_id,archived,revision,created_at,updated_at)
                VALUES (:id,:spaceId,:title,NULL,false,1,:at,:at)
                """)
                .param("id", document.documentId())
                .param("spaceId", document.spaceId())
                .param("title", document.title())
                .param("at", at)
                .update();
        jdbc.sql("""
                INSERT INTO document_version(
                    id,document_id,version_number,source_blob_id,status,parse_status,
                    created_by,created_at,status_changed_by,status_changed_at)
                VALUES (:id,:documentId,1,:sourceBlobId,'PUBLISHED','SUCCEEDED',
                        :actorId,:at,:actorId,:at)
                """)
                .param("id", document.versionId())
                .param("documentId", document.documentId())
                .param("sourceBlobId", document.sourceBlobId())
                .param("actorId", EVALUATION_ACTOR)
                .param("at", at)
                .update();
        jdbc.sql("""
                INSERT INTO canonical_document(version_id,source_filename,media_type,page_count,parsed_at)
                VALUES (:versionId,:sourceFilename,'text/markdown',1,:at)
                """)
                .param("versionId", document.versionId())
                .param("sourceFilename", document.sourceFilename())
                .param("at", at)
                .update();
        jdbc.sql("""
                INSERT INTO canonical_node(
                    version_id,node_id,kind,body_text,parent_node_id,page,
                    bounding_box,table_cells,confidence,stable_order)
                VALUES (:versionId,:nodeId,'PARAGRAPH',:body,NULL,NULL,NULL,NULL,1.0,0)
                """)
                .param("versionId", document.versionId())
                .param("nodeId", document.nodeId())
                .param("body", document.body())
                .update();
        jdbc.sql("""
                UPDATE document SET current_published_version_id=:versionId
                WHERE id=:documentId
                """)
                .param("versionId", document.versionId())
                .param("documentId", document.documentId())
                .update();
    }

    private List<DesiredDocument> desiredDocuments() {
        validateDatasetIdentity();
        Map<UUID, DesiredDocument> documents = new LinkedHashMap<>();

        dataset.cases().stream()
                .sorted(Comparator.comparing(EvaluationCase::id))
                .forEach(item -> item.expectedEvidence().stream()
                        .sorted(Comparator.comparing(EvaluationCase.EvidenceRef::documentId)
                                .thenComparing(EvaluationCase.EvidenceRef::nodeId))
                        .forEach(evidence -> putUnique(documents, new DesiredDocument(
                                evidence.documentId(), ACCESSIBLE_SPACE, evidence.nodeId(),
                                "黄金评测已授权证据 " + evidence.documentId(),
                                "评测问题：" + item.question() + "\n虚构标准结论：应基于本条已授权知识作答，并引用本证据节点。"))));

        dataset.cases().stream()
                .filter(item -> item.coverageTags().contains("CONFLICT"))
                .sorted(Comparator.comparing(EvaluationCase::id))
                .forEach(item -> {
                    UUID first = deterministicId("conflict:" + item.id() + ":approved");
                    UUID second = deterministicId("conflict:" + item.id() + ":rejected");
                    putUnique(documents, new DesiredDocument(
                            first, ACCESSIBLE_SPACE, "conflict-approved-" + item.id(),
                            "黄金评测冲突证据 A " + item.id(),
                            "评测问题：" + item.question() + "\n虚构结论 A：该方案已经正式批准并立即生效。"));
                    putUnique(documents, new DesiredDocument(
                            second, ACCESSIBLE_SPACE, "conflict-rejected-" + item.id(),
                            "黄金评测冲突证据 B " + item.id(),
                            "评测问题：" + item.question() + "\n虚构结论 B：该方案明确未获批准且禁止执行。"));
                });

        dataset.cases().stream()
                .filter(item -> !item.forbiddenDocumentIds().isEmpty()
                        || !item.forbiddenNodeIds().isEmpty())
                .sorted(Comparator.comparing(EvaluationCase::id))
                .forEach(item -> {
                    if (item.forbiddenDocumentIds().size() != 1 || item.forbiddenNodeIds().size() != 1) {
                        throw new IllegalStateException(CONFLICT_CODE);
                    }
                    UUID documentId = item.forbiddenDocumentIds().iterator().next();
                    String nodeId = item.forbiddenNodeIds().iterator().next();
                    putUnique(documents, new DesiredDocument(
                            documentId, RESTRICTED_SPACE, nodeId,
                            "黄金评测越权陷阱 " + documentId,
                            "评测问题：" + item.question() + "\n虚构受限结论：此内容用于验证越权证据绝不能进入回答。"));
                });

        return List.copyOf(documents.values());
    }

    private void validateDatasetIdentity() {
        Set<UUID> actors = dataset.cases().stream()
                .map(EvaluationCase::evaluationActorId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!dataset.active() || !actors.equals(Set.of(EVALUATION_ACTOR))) throw conflict();
    }

    private static void putUnique(Map<UUID, DesiredDocument> documents, DesiredDocument document) {
        DesiredDocument previous = documents.putIfAbsent(document.documentId(), document);
        if (previous != null && !previous.equals(document)) throw conflict();
    }

    private boolean exists(String table, UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id=:id)")
                .param("id", id)
                .query(Boolean.class).single();
    }

    private static UUID deterministicId(String value) {
        return UUID.nameUUIDFromBytes(("elite-rag:golden-dataset-v1:" + value)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException(CONFLICT_CODE);
    }

    public record ProvisioningResult(boolean installed, int accessibleDocuments, int restrictedDocuments) { }

    private record DesiredDocument(
            UUID documentId, UUID spaceId, String nodeId, String title, String body) {
        DesiredDocument {
            Objects.requireNonNull(documentId);
            Objects.requireNonNull(spaceId);
            Objects.requireNonNull(nodeId);
            Objects.requireNonNull(title);
            Objects.requireNonNull(body);
        }

        UUID versionId() {
            return deterministicId("version:" + documentId);
        }

        String sourceBlobId() {
            return "fixture://golden-v1/" + documentId;
        }

        String sourceFilename() {
            return documentId + ".md";
        }
    }
}
