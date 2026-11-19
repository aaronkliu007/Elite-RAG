package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.qa.ModelCallAuditPort;
import com.yunjihai.rag.qa.QaPersistencePort;
import com.yunjihai.rag.qa.domain.*;
import com.yunjihai.rag.qa.model.ModelUsage;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcQaPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-10T08:00:00Z");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_qa_persistence_test").withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static JdbcClient jdbc;
    private static JdbcClient ownerJdbc;
    private static JdbcQaPersistenceAdapter persistence;
    private static JdbcModelCallAuditAdapter audits;
    private UUID actor, request, space, document, version;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_qa_persistence_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        ownerJdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        jdbc = JdbcClient.create(dataSource);
        persistence = new JdbcQaPersistenceAdapter(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
        audits = new JdbcModelCallAuditAdapter(jdbc);
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @BeforeEach
    void seed() {
        for (String table : List.of("qa_model_call_audit", "qa_answer", "qa_evidence_snapshot", "qa_request"))
            ownerJdbc.sql("DELETE FROM " + table).update();
        jdbc.sql("DELETE FROM retrieval_projection_event").update();
        jdbc.sql("DELETE FROM acl_entry").update();
        jdbc.sql("UPDATE document SET current_published_version_id=NULL").update();
        for (String table : List.of("canonical_node", "canonical_document", "document_version",
                "document", "knowledge_space", "app_user"))
            jdbc.sql("DELETE FROM " + table).update();
        actor=UUID.randomUUID(); request=UUID.randomUUID(); space=UUID.randomUUID();
        document=UUID.randomUUID(); version=UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,external_id,display_name) VALUES (:id,:ext,'Reader')")
                .param("id",actor).param("ext",actor.toString()).update();
        jdbc.sql("INSERT INTO knowledge_space(id,name,created_by) VALUES (:id,'Space',:actor)")
                .param("id",space).param("actor",actor).update();
        jdbc.sql("INSERT INTO document(id,space_id,title) VALUES (:id,:space,'制度')")
                .param("id",document).param("space",space).update();
        jdbc.sql("""
                INSERT INTO document_version(id,document_id,version_number,source_blob_id,status,parse_status,created_by)
                VALUES (:id,:document,1,'blob','PUBLISHED','SUCCEEDED',:actor)
                """).param("id",version).param("document",document).param("actor",actor).update();
        jdbc.sql("UPDATE document SET current_published_version_id=:version WHERE id=:document")
                .param("version",version).param("document",document).update();
        jdbc.sql("""
                INSERT INTO canonical_document(version_id,source_filename,media_type,page_count,parsed_at)
                VALUES (:version,'policy.md','text/markdown',1,:at)
                """).param("version",version).param("at",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO canonical_node(version_id,node_id,kind,body_text,stable_order)
                VALUES (:version,'node-1','PARAGRAPH','绝不能入库的证据正文',0)
                """).param("version",version).update();
    }

    @Test
    void persistsEvidenceIdentityAndAnswerButNotRawEvidenceOrQuestion() {
        var created=persistence.create(request,actor,"a".repeat(64),NOW);
        assertThat(persistence.create(request,actor,"a".repeat(64),NOW)).isEqualTo(created);
        var retrieving=persistence.transition(request,0,QaStatus.RETRIEVING,NOW).orElseThrow();
        UUID evidenceId=UUID.randomUUID();
        String body="绝不能入库的证据正文";
        var item=new QaEvidence(evidenceId,space,document,version,"node-1","制度",body,0.9,
                new MarkdownLocator(List.of("第一章"),"node-1"));
        var sanitizing=persistence.saveEvidence(request,retrieving.revision(),
                new EvidenceBundle(List.of(item),body.codePointCount(0,body.length())),false,NOW).orElseThrow();
        var generating=persistence.transition(request,sanitizing.revision(),QaStatus.GENERATING,NOW).orElseThrow();
        var verifying=persistence.transition(request,generating.revision(),QaStatus.VERIFYING,NOW).orElseThrow();
        var claim=new CitationClaim(UUID.randomUUID(),"制度生效",List.of(evidenceId));
        var answered=new QaOutcome.Answered(request,"制度已经生效。",List.of(claim),false);
        assertThat(persistence.complete(request,verifying.revision(),answered,NOW)).isPresent();
        assertThat(persistence.find(request,actor)).contains(answered);
        assertThat(persistence.completedAt(request,actor)).contains(NOW);
        assertThat(persistence.completedAt(request,UUID.randomUUID())).isEmpty();
        assertThat(jdbc.sql("SELECT title FROM qa_evidence_snapshot WHERE request_id=:id")
                .param("id",request).query(String.class).single()).isEqualTo("制度");
        String storedClaims=jdbc.sql("SELECT claims::text FROM qa_answer WHERE request_id=:id")
                .param("id",request).query(String.class).single();
        assertThat(storedClaims).contains(evidenceId.toString(),"evidenceIds")
                .doesNotContain(body,"quote","supports");
        assertThat(jdbc.sql("SELECT content_digest FROM qa_evidence_snapshot WHERE request_id=:id")
                .param("id",request).query(String.class).single()).matches("[0-9a-f]{64}");
        assertThat(jdbc.sql("SELECT count(*) FROM retrieval_projection_event").query(Long.class).single()).isZero();
    }

    @Test
    void rejectsStaleRevisionAndPersistsOnlyApprovedModelAuditFields() {
        persistence.create(request,actor,"b".repeat(64),NOW);
        assertThat(persistence.transition(request,9,QaStatus.RETRIEVING,NOW)).isEmpty();
        audits.record(new ModelCallAuditPort.ModelCallAudit(request,
                "approved-openai-compatible","chat-completions","chat-model","model-config-v1",
                "c".repeat(64),"d".repeat(64),
                new ModelUsage(100,20,Duration.ofMillis(50)),2,1,"SUCCESS",NOW,"qa-scoped-test-v1"));
        var stored=jdbc.sql("""
                SELECT provider,endpoint_identifier,model_name,model_config_version,
                       request_digest,response_digest,input_tokens,output_tokens,latency_ms,
                       sanitization_hit_count,retry_count,result_code,qa_policy_version
                FROM qa_model_call_audit WHERE request_id=:id
                """).param("id",request).query((row,n)->List.of(
                        row.getString(1),row.getString(2),row.getString(3),row.getString(4),
                        row.getString(5),row.getString(6),row.getInt(7),row.getInt(8),row.getLong(9),
                        row.getInt(10),row.getInt(11),row.getString(12),row.getString(13))).single();
        assertThat(stored).containsExactly(
                "approved-openai-compatible","chat-completions","chat-model","model-config-v1",
                "c".repeat(64),"d".repeat(64),100,20,50L,2,1,"SUCCESS","qa-scoped-test-v1");
    }

    @Test
    void rechecksNonSelectedScreeningDocumentsBeforeReturningScopedAnswer() throws Exception {
        UUID secondDocument=UUID.randomUUID(),secondVersion=UUID.randomUUID();
        jdbc.sql("INSERT INTO document(id,space_id,title) VALUES (:id,:space,'冲突来源')")
                .param("id",secondDocument).param("space",space).update();
        jdbc.sql("""
                INSERT INTO document_version(id,document_id,version_number,source_blob_id,status,parse_status,created_by)
                VALUES (:id,:document,1,'second-blob','PUBLISHED','SUCCEEDED',:actor)
                """).param("id",secondVersion).param("document",secondDocument).param("actor",actor).update();
        jdbc.sql("UPDATE document SET current_published_version_id=:version WHERE id=:document")
                .param("version",secondVersion).param("document",secondDocument).update();
        jdbc.sql("INSERT INTO canonical_document(version_id,source_filename,media_type,page_count,parsed_at) VALUES (:version,'other.md','text/markdown',1,:at)")
                .param("version",secondVersion).param("at",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC)).update();
        jdbc.sql("INSERT INTO canonical_node(version_id,node_id,kind,body_text,stable_order) VALUES (:version,'node-2','PARAGRAPH','冲突资料',0)")
                .param("version",secondVersion).update();
        jdbc.sql("INSERT INTO acl_entry(id,space_id,scope,principal_type,principal_id) VALUES (:id,:space,'SPACE','USER',:actor)")
                .param("id",UUID.randomUUID()).param("space",space).param("actor",actor).update();
        var locator=new MarkdownLocator(List.of("章节"),"node-1");
        UUID selectedId=UUID.randomUUID();
        var selected=new QaEvidence(selectedId,space,document,version,"node-1","制度","绝不能入库的证据正文",0.9,locator);
        var first=new com.yunjihai.rag.retrieval.domain.Evidence(space,selectedId,document,version,"node-1","制度",selected.excerpt(),0.9,locator);
        var second=new com.yunjihai.rag.retrieval.domain.Evidence(space,UUID.randomUUID(),secondDocument,secondVersion,"node-2","冲突来源","冲突资料",0.1,
                new MarkdownLocator(List.of("章节"),"node-2"));
        persistence.create(request,actor,"a".repeat(64),NOW);
        var retrieving=persistence.transition(request,0,QaStatus.RETRIEVING,NOW).orElseThrow();
        var saved=persistence.saveEvidence(request,retrieving.revision(),new EvidenceBundle(List.of(selected),selected.excerpt().length()),false,NOW).orElseThrow();
        assertThat(persistence.saveConflictScreening(request,saved.revision(),List.of(first,second))).isTrue();
        var auth=new JdbcQaEvidenceAuthorizationAdapter(jdbc);
        assertThat(auth.allAuthorized(request,actor,NOW)).isTrue();
        UUID priorRequest=UUID.randomUUID();
        persistence.create(priorRequest,actor,"b".repeat(64),NOW);
        var priorRetrieving=persistence.transition(priorRequest,0,QaStatus.RETRIEVING,NOW).orElseThrow();
        var priorSaved=persistence.saveEvidence(priorRequest,priorRetrieving.revision(),
                new EvidenceBundle(List.of(selected),selected.excerpt().length()),false,NOW).orElseThrow();
        assertThat(persistence.saveConflictScreening(priorRequest,priorSaved.revision(),List.of(first,second))).isTrue();
        var priorGenerating=persistence.transition(priorRequest,priorSaved.revision(),QaStatus.GENERATING,NOW).orElseThrow();
        var priorVerifying=persistence.transition(priorRequest,priorGenerating.revision(),QaStatus.VERIFYING,NOW).orElseThrow();
        var priorAnswer=new QaOutcome.Answered(priorRequest,"制度",List.of(new CitationClaim(UUID.randomUUID(),"制度",List.of(selectedId))),false);
        assertThat(persistence.completeAuthorized(priorRequest,priorVerifying.revision(),priorAnswer,NOW,
                ()->auth.allAuthorized(priorRequest,actor,NOW)).authorized()).isTrue();
        assertThat(persistence.findAuthorized(priorRequest,actor,()->auth.allAuthorized(priorRequest,actor,NOW))).isPresent();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var firstRead=executor.submit(()->persistence.findAuthorized(priorRequest,actor,()->auth.allAuthorized(priorRequest,actor,NOW)));
            var secondRead=executor.submit(()->persistence.findAuthorized(priorRequest,actor,()->auth.allAuthorized(priorRequest,actor,NOW)));
            assertThat(firstRead.get(10,TimeUnit.SECONDS)).isPresent();
            assertThat(secondRead.get(10,TimeUnit.SECONDS)).isPresent();
        }
        jdbc.sql("UPDATE canonical_node SET body_text='新增约束' WHERE version_id=:version AND node_id='node-2'")
                .param("version",secondVersion).update();
        assertThat(persistence.findAuthorized(priorRequest,actor,()->auth.allAuthorized(priorRequest,actor,NOW))).isEmpty();
        jdbc.sql("UPDATE canonical_node SET body_text='冲突资料' WHERE version_id=:version AND node_id='node-2'")
                .param("version",secondVersion).update();
        assertThat(persistence.findAuthorized(priorRequest,actor,()->auth.allAuthorized(priorRequest,actor,NOW))).isPresent();
        jdbc.sql("INSERT INTO acl_entry(id,space_id,document_id,scope,principal_type,principal_id,revoked_at) VALUES (:id,:space,:document,'DOCUMENT','USER',:actor,:at)")
                .param("id",UUID.randomUUID()).param("space",space).param("document",secondDocument).param("actor",actor)
                .param("at",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC)).update();
        assertThat(auth.allAuthorized(request,actor,NOW)).isFalse();
        assertThat(persistence.findAuthorized(priorRequest,actor,()->auth.allAuthorized(priorRequest,actor,NOW))).isEmpty();
        var generating=persistence.transition(request,saved.revision(),QaStatus.GENERATING,NOW).orElseThrow();
        var verifying=persistence.transition(request,generating.revision(),QaStatus.VERIFYING,NOW).orElseThrow();
        var answer=new QaOutcome.Answered(request,"制度",List.of(new CitationClaim(UUID.randomUUID(),"制度",List.of(selectedId))),false);
        var completion=persistence.completeAuthorized(request,verifying.revision(),answer,NOW,()->auth.allAuthorized(request,actor,NOW));
        assertThat(completion.authorized()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM qa_answer WHERE request_id=:id").param("id",request).query(Long.class).single()).isZero();
    }

    @Test
    void preservesDegradedFlagForFailedTerminalResults() {
        persistence.create(request,actor,"e".repeat(64),NOW);
        var retrieving=persistence.transition(request,0,QaStatus.RETRIEVING,NOW).orElseThrow();
        var failed=new QaOutcome.Failed(request,"MODEL_UNAVAILABLE",true);

        assertThat(persistence.complete(request,retrieving.revision(),failed,NOW)).isPresent();

        assertThat(persistence.find(request,actor)).contains(failed);
        assertThat(ownerJdbc.sql("SELECT degraded FROM qa_request WHERE id=:id")
                .param("id",request).query(Boolean.class).single()).isTrue();
    }

    @Test
    void concurrentTransitionsAcceptOnlyOneWriterForTheSameRevision() throws Exception {
        persistence.create(request,actor,"f".repeat(64),NOW);
        var ready=new CountDownLatch(2);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Optional<QaPersistencePort.RequestState>> attempt=()->{
                ready.countDown();
                start.await();
                return persistence.transition(request,0,QaStatus.RETRIEVING,NOW);
            };
            var first=executor.submit(attempt);
            var second=executor.submit(attempt);
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(),second.get())).filteredOn(Optional::isPresent).hasSize(1);
        }
        assertThat(ownerJdbc.sql("SELECT status || ':' || revision FROM qa_request WHERE id=:id")
                .param("id",request).query(String.class).single()).isEqualTo("RETRIEVING:1");
    }

    @Test
    void evidenceForeignKeyFailureRollsBackStateAndAllSnapshots() {
        persistence.create(request,actor,"9".repeat(64),NOW);
        var retrieving=persistence.transition(request,0,QaStatus.RETRIEVING,NOW).orElseThrow();
        var invalid=new QaEvidence(UUID.randomUUID(),space,document,version,"missing-node","制度",
                "不能部分写入",0.8,new MarkdownLocator(List.of("第一章"),"missing-node"));

        assertThatThrownBy(()->persistence.saveEvidence(request,retrieving.revision(),
                new EvidenceBundle(List.of(invalid),invalid.excerpt().codePointCount(0,invalid.excerpt().length())),
                false,NOW)).isInstanceOf(RuntimeException.class);

        assertThat(ownerJdbc.sql("SELECT status || ':' || revision FROM qa_request WHERE id=:id")
                .param("id",request).query(String.class).single()).isEqualTo("RETRIEVING:1");
        assertThat(ownerJdbc.sql("SELECT count(*) FROM qa_evidence_snapshot WHERE request_id=:id")
                .param("id",request).query(Long.class).single()).isZero();
    }

    @Test
    void unknownAnswerCitationRollsBackTerminalStateAndAnswer() {
        persistence.create(request,actor,"8".repeat(64),NOW);
        var retrieving=persistence.transition(request,0,QaStatus.RETRIEVING,NOW).orElseThrow();
        UUID evidenceId=UUID.randomUUID();
        var item=new QaEvidence(evidenceId,space,document,version,"node-1","制度","合法证据",0.9,
                new MarkdownLocator(List.of("第一章"),"node-1"));
        var sanitizing=persistence.saveEvidence(request,retrieving.revision(),
                new EvidenceBundle(List.of(item),4),false,NOW).orElseThrow();
        var generating=persistence.transition(request,sanitizing.revision(),QaStatus.GENERATING,NOW).orElseThrow();
        var verifying=persistence.transition(request,generating.revision(),QaStatus.VERIFYING,NOW).orElseThrow();
        var invalid=new QaOutcome.Answered(request,"回答",
                List.of(new CitationClaim(UUID.randomUUID(),"结论",List.of(UUID.randomUUID()))),false);

        assertThatThrownBy(()->persistence.complete(request,verifying.revision(),invalid,NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("QA_ANSWER_REFERENCES_UNKNOWN_EVIDENCE");

        assertThat(ownerJdbc.sql("SELECT status || ':' || revision FROM qa_request WHERE id=:id")
                .param("id",request).query(String.class).single()).isEqualTo("VERIFYING:4");
        assertThat(ownerJdbc.sql("SELECT count(*) FROM qa_answer WHERE request_id=:id")
                .param("id",request).query(Long.class).single()).isZero();
    }
}
