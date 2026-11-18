package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.qa.evaluation.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcEvaluationRepositoryIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_evaluation_test").withUsername("migration_owner").withPassword("migration_test_only");
    private static JdbcClient owner;
    private static JdbcEvaluationRepository repository;
    private static EvaluationDataset dataset;
    private static EvaluationTraceRegistry traces;

    @BeforeAll static void start() throws Exception {
        POSTGRES.start();
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            var statement=connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_evaluation_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner=JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        var runtime=JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"rag_runtime_test","runtime_test_only"));
        dataset=dataset();
        traces=new EvaluationTraceRegistry();
        repository=new JdbcEvaluationRepository(runtime,new ObjectMapper().findAndRegisterModules(),Map.of(dataset.id(),dataset),traces);
    }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void clean() {
        owner.sql("DELETE FROM qa_evaluation_run").update();
        owner.sql("DELETE FROM qa_request").update();
        owner.sql("DELETE FROM canonical_node").update();
        owner.sql("DELETE FROM canonical_document").update();
        owner.sql("UPDATE document SET current_published_version_id=NULL").update();
        owner.sql("DELETE FROM document_version").update();
        owner.sql("DELETE FROM document").update();
        owner.sql("DELETE FROM knowledge_space").update();
        owner.sql("DELETE FROM app_user").update();
    }

    @Test
    void persistsImmutableVersionedRunAndLoadsGoldenCatalog() {
        UUID runId=UUID.randomUUID();
        var run=new EvaluationRepository.EvaluationRun(runId,dataset.id(),dataset.version(),"code-1","policy-2",
                dataset.promptVersion(),dataset.sanitizationVersion(),"model-3",Instant.EPOCH,Instant.EPOCH.plusSeconds(3),
                new EvaluationMetrics(100,90,10,10,10,10,10,10,0),new EvaluationGate.Assessment(true,List.of()));

        repository.save(run);

        assertThat(repository.findDataset(dataset.id())).contains(dataset);
        assertThat(repository.findRun(runId)).contains(run);
        owner.sql("UPDATE qa_evaluation_run SET metrics=metrics-'reviewedClaimCount'-'generatedClaimCount' WHERE id=:id")
                .param("id",runId).update();
        var historical=repository.findRun(runId).orElseThrow();
        assertThat(historical.assessment().passed()).isTrue();
        assertThat(historical.currentAssessment().passed()).isFalse();
        assertThat(historical.currentAssessment().failures()).contains(EvaluationGate.Failure.CLAIM_REVIEW_COVERAGE);
        assertThat(owner.sql("SELECT code_version FROM qa_evaluation_run WHERE id=:id")
                .param("id",runId).query(String.class).single()).isEqualTo("code-1");
        assertThat(owner.sql("""
                SELECT privilege_type FROM information_schema.role_table_grants
                WHERE grantee='rag_runtime_test' AND table_name='qa_evaluation_run'
                ORDER BY privilege_type
                """).query(String.class).list()).containsExactly("INSERT","SELECT");
    }

    @Test
    void persistsIrrelevantClaimCountAndKeepsRelevanceGateFailed() {
        String digest=EvaluationDatasetFingerprint.sha256(dataset);
        var metrics=new EvaluationMetrics(100,100,100,100,100,99,1,1,0,100,100,1);
        var result=new EvaluationCaseResult(UUID.randomUUID(),UUID.randomUUID(),true,
                com.yunjihai.rag.qa.domain.QaStatus.REFUSED,"INSUFFICIENT_EVIDENCE",0,0,0,0,0,0);
        var run=new EvaluationRepository.EvaluationRun(UUID.randomUUID(),dataset.id(),dataset.version(),"code","policy",
                dataset.promptVersion(),dataset.sanitizationVersion(),"model",Instant.EPOCH,Instant.EPOCH,
                metrics,new EvaluationGate().assess(metrics),digest,digest,List.of(result));
        repository.save(run);
        var loaded=repository.findRun(run.runId()).orElseThrow().withCurrentDatasetDigest(digest);
        assertThat(loaded.metrics().irrelevantClaimCount()).isEqualTo(1);
        assertThat(loaded.currentAssessment().failures()).contains(EvaluationGate.Failure.QUESTION_RELEVANCE);
    }

    @Test
    void persistsCaseDiagnosticsWithoutQuestionOrEvidenceText() {
        String digest=EvaluationDatasetFingerprint.sha256(dataset);
        var result=new EvaluationCaseResult(UUID.randomUUID(),UUID.randomUUID(),false,
                com.yunjihai.rag.qa.domain.QaStatus.REFUSED,"CONFLICTING_EVIDENCE",2,1,0,0,0,0);
        var run=new EvaluationRepository.EvaluationRun(UUID.randomUUID(),dataset.id(),dataset.version(),"code","policy",
                dataset.promptVersion(),dataset.sanitizationVersion(),"model",Instant.EPOCH,Instant.EPOCH,
                new EvaluationMetrics(2,1,1,0,1,0,1,1,0),new EvaluationGate.Assessment(false,List.of()),digest,digest,List.of(result));
        repository.save(run);
        assertThat(repository.findRun(run.runId()).orElseThrow().caseResults()).containsExactly(result);
        String stored=owner.sql("SELECT case_results::text FROM qa_evaluation_run WHERE id=:id")
                .param("id",run.runId()).query(String.class).single();
        assertThat(stored).doesNotContain("question","answer","excerpt","title");
        owner.sql("UPDATE qa_evaluation_run SET case_results=jsonb_build_array((case_results->0)-'reviewBinding') WHERE id=:id")
                .param("id",run.runId()).update();
        assertThat(repository.findRun(run.runId()).orElseThrow().caseResults()).containsExactly(result);
    }

    @Test
    void persistsDatasetFingerprintForCurrentGateReassessment() {
        String digest=EvaluationDatasetFingerprint.sha256(dataset);
        var run=new EvaluationRepository.EvaluationRun(UUID.randomUUID(),dataset.id(),dataset.version(),"code","policy",
                dataset.promptVersion(),dataset.sanitizationVersion(),"model",Instant.EPOCH,Instant.EPOCH,
                new EvaluationMetrics(1,1,1,1,1,1,1,1,0,1),new EvaluationGate.Assessment(true,List.of()),digest,digest,List.of(
                        new EvaluationCaseResult(UUID.randomUUID(),UUID.randomUUID(),true,
                                com.yunjihai.rag.qa.domain.QaStatus.REFUSED,"INSUFFICIENT_EVIDENCE",0,0,0,0,0,0)));
        repository.save(run);
        var loaded=repository.findRun(run.runId()).orElseThrow();
        assertThat(loaded.recordedDatasetDigest()).isEqualTo(digest);
        assertThat(loaded.currentAssessment().failures()).contains(EvaluationGate.Failure.DATASET_PROVENANCE);
        assertThat(loaded.withCurrentDatasetDigest(digest).currentAssessment().passed()).isTrue();
    }

    @Test
    void historicalInvalidModelOutputFailsCurrentGateAfterDatabaseRoundTrip() {
        String digest=EvaluationDatasetFingerprint.sha256(dataset);
        var metrics=new EvaluationMetrics(1,1,1,1,1,1,1,1,0,1);
        var caseResult=new EvaluationCaseResult(UUID.randomUUID(),UUID.randomUUID(),true,
                com.yunjihai.rag.qa.domain.QaStatus.REFUSED,"INVALID_MODEL_OUTPUT",0,0,0,0,0,0);
        var run=new EvaluationRepository.EvaluationRun(UUID.randomUUID(),dataset.id(),dataset.version(),"code","policy",
                dataset.promptVersion(),dataset.sanitizationVersion(),"model",Instant.EPOCH,Instant.EPOCH,
                metrics,new EvaluationGate.Assessment(true,List.of()),digest,digest,List.of(caseResult));
        repository.save(run);
        var loaded=repository.findRun(run.runId()).orElseThrow().withCurrentDatasetDigest(digest);
        assertThat(loaded.assessment().passed()).isTrue();
        assertThat(loaded.currentAssessment().failures()).contains(EvaluationGate.Failure.CORRECT_REFUSAL);
        assertThat(loaded.currentAssessment().passed()).isFalse();
    }
    @Test
    void persistsCompletedConflictMarkerAndTreatsLegacyMissingMarkerAsUnverified() {
        String digest=EvaluationDatasetFingerprint.sha256(dataset);
        var metrics=new EvaluationMetrics(1,1,1,1,1,1,1,1,0,1);
        var result=new EvaluationCaseResult(UUID.randomUUID(),UUID.randomUUID(),true,
                com.yunjihai.rag.qa.domain.QaStatus.REFUSED,"CONFLICTING_EVIDENCE",0,0,0,0,0,0,null,true);
        var run=new EvaluationRepository.EvaluationRun(UUID.randomUUID(),dataset.id(),dataset.version(),"code","policy",
                dataset.promptVersion(),dataset.sanitizationVersion(),"model",Instant.EPOCH,Instant.EPOCH,
                metrics,new EvaluationGate.Assessment(true,List.of()),digest,digest,List.of(result));
        repository.save(run);
        var loaded=repository.findRun(run.runId()).orElseThrow().withCurrentDatasetDigest(digest);
        assertThat(loaded.caseResults().getFirst().conflictScreeningCompleted()).isTrue();
        assertThat(loaded.currentAssessment().passed()).isTrue();

        owner.sql("UPDATE qa_evaluation_run SET case_results=jsonb_build_array((case_results->0)-'conflictScreeningCompleted') WHERE id=:id")
                .param("id",run.runId()).update();
        var legacy=repository.findRun(run.runId()).orElseThrow().withCurrentDatasetDigest(digest);
        assertThat(legacy.assessment().passed()).isTrue();
        assertThat(legacy.caseResults().getFirst().conflictScreeningCompleted()).isFalse();
        assertThat(legacy.currentAssessment().failures()).contains(EvaluationGate.Failure.REFUSAL_DIAGNOSTICS_INCOMPLETE);
    }
    @Test
    void observationCarriesExplicitCompletedConflictScreeningTrace() {
        UUID request=UUID.randomUUID();
        traces.begin(request);
        traces.recordConflictScreeningCompleted(request);
        var observation=repository.observe(request);
        assertThat(observation.conflictScreeningCompleted()).isTrue();
        assertThat(observation.conflictScreeningUnavailable()).isFalse();
        assertThat(repository.observe(UUID.randomUUID()).conflictScreeningCompleted()).isFalse();
    }
    @Test
    void observesTopTenEvidenceWithoutPersistingRawModelPayloads() throws Exception {
        UUID actor=UUID.randomUUID(),space=UUID.randomUUID(),document=UUID.randomUUID();
        UUID version=UUID.randomUUID(),request=UUID.randomUUID();
        owner.sql("INSERT INTO app_user(id,external_id,display_name) VALUES (:id,:ext,'Evaluation User')")
                .param("id",actor).param("ext",actor.toString()).update();
        owner.sql("INSERT INTO knowledge_space(id,name,created_by) VALUES (:id,'Evaluation Space',:actor)")
                .param("id",space).param("actor",actor).update();
        owner.sql("INSERT INTO document(id,space_id,title) VALUES (:id,:space,'Fictional Evaluation Document')")
                .param("id",document).param("space",space).update();
        owner.sql("""
                INSERT INTO document_version(id,document_id,version_number,source_blob_id,status,parse_status,created_by)
                VALUES (:id,:document,1,'blob','PUBLISHED','SUCCEEDED',:actor)
                """).param("id",version).param("document",document).param("actor",actor).update();
        owner.sql("""
                INSERT INTO canonical_document(version_id,source_filename,media_type,page_count,parsed_at)
                VALUES (:id,'fictional.md','text/markdown',1,now())
                """)
                .param("id",version).update();
        owner.sql("""
                INSERT INTO qa_request(id,actor_id,question_digest,status,revision,degraded,created_at,updated_at)
                VALUES (:id,:actor,:digest,'RETRIEVING',1,false,now(),now())
                """).param("id",request).param("actor",actor).param("digest","a".repeat(64)).update();
        List<UUID> evidenceIds=new ArrayList<>();
        for(int ordinal=0;ordinal<11;ordinal++) {
            String node="fictional-node-"+ordinal;
            UUID evidenceId=UUID.randomUUID(); evidenceIds.add(evidenceId);
            owner.sql("""
                    INSERT INTO canonical_node(version_id,node_id,kind,body_text,stable_order)
                    VALUES (:version,:node,'PARAGRAPH','fictional body',:ordinal)
                    """).param("version",version).param("node",node).param("ordinal",ordinal).update();
            owner.sql("""
                    INSERT INTO qa_evidence_snapshot(request_id,evidence_id,ordinal,space_id,document_id,
                        version_id,node_id,content_digest,title,score,locator_type,locator)
                    VALUES (:request,:evidence,:ordinal,:space,:document,:version,:node,:digest,
                        'Fictional Source',0.9,'MARKDOWN',CAST(:locator AS jsonb))
                    """).param("request",request).param("evidence",evidenceId).param("ordinal",ordinal)
                    .param("space",space).param("document",document).param("version",version).param("node",node)
                    .param("digest","b".repeat(64)).param("locator","{\"headingPath\":[\"Fictional\"],\"nodeId\":\""+node+"\"}").update();
        }

        var requestRef=new EvaluationCase.EvidenceRef(document,"fictional-node-10");
        var responseRef=new EvaluationCase.EvidenceRef(document,"fictional-node-1");
        String locatorText=owner.sql("SELECT locator::text FROM qa_evidence_snapshot WHERE request_id=:request AND ordinal=0")
                .param("request",request).query(String.class).single();
        traces.begin(request);
        traces.recordRequest(request,List.of(requestRef));
        traces.recordResponse(request,List.of(responseRef));
        var observation=repository.observe(request);

        assertThat(observation.evidenceIds()).containsExactlyInAnyOrderElementsOf(evidenceIds);
        assertThat(observation.evidenceReferences().get(evidenceIds.getFirst()))
                .isEqualTo(new EvaluationCase.ReviewedEvidence(document,version,"fictional-node-0","b".repeat(64),
                        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest("Fictional Source".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                .digest(locatorText.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        assertThat(observation.retrievedTop10()).hasSize(10)
                .doesNotContain(new EvaluationCase.EvidenceRef(document,"fictional-node-10"));
        assertThat(observation.modelRequestTrace()).containsExactly(requestRef);
        assertThat(observation.responseTrace()).containsExactly(responseRef);
        assertThat(traces.consume(request).modelRequest()).isEmpty();
    }

    @Test
    void observesAuthorizedRetrievalEvenWhenNoEvidenceSnapshotWasSaved() {
        UUID request = UUID.randomUUID(), document = UUID.randomUUID();
        var reference = new EvaluationCase.EvidenceRef(document, "synthetic-node");
        traces.begin(request);
        traces.recordRetrieval(request, List.of(reference));
        var observation = repository.observe(request);
        assertThat(observation.retrievedTop10()).containsExactly(reference);
        assertThat(observation.evidenceIds()).isEmpty();
    }

    @Test
    void observesConflictScreeningFailureWithoutEvidenceOrSensitiveContent() {
        UUID request = UUID.randomUUID();
        traces.begin(request);
        traces.recordConflictScreeningUnavailable(request);
        var observation = repository.observe(request);
        assertThat(observation.conflictScreeningUnavailable()).isTrue();
        assertThat(observation.evidenceIds()).isEmpty();
        assertThat(traces.consume(request).conflictScreeningUnavailable()).isFalse();
    }

    private static EvaluationDataset dataset() {
        UUID actor=UUID.randomUUID(),document=UUID.randomUUID();
        List<EvaluationCase> cases=new ArrayList<>();
        for(int index=0;index<50;index++)cases.add(new EvaluationCase(UUID.randomUUID(),actor,
                "虚构问题 "+index,Set.of(new EvaluationCase.EvidenceRef(document,"node-1")),
                Set.of(),Set.of(),false,Set.of("PDF")));
        return new EvaluationDataset(UUID.randomUUID(),"golden-v1","prompt-v1","sanitizer-v1",cases,true);
    }
}
