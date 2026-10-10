package com.yunjihai.rag.infrastructure.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.qa.domain.*;
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
import static org.assertj.core.api.Assertions.assertThat;

class JdbcQaSourceAccessIntegrationTest {
    private static final Instant NOW=Instant.parse("2026-10-10T08:00:00Z");
    private static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_qa_source_test").withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static JdbcClient jdbc;
    private static JdbcClient ownerJdbc;
    private static JdbcQaPersistenceAdapter persistence;
    private static JdbcAuthorizedSpaceScope scopes;
    private static JdbcQaSourceAccessAdapter sources;
    private UUID actor,request,evidence,uncitedEvidence,space,document,version;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        try(var connection=java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            var statement=connection.createStatement()){
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_qa_source_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        ownerJdbc=JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        var dataSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"rag_runtime_test","runtime_test_only");
        jdbc=JdbcClient.create(dataSource);
        persistence=new JdbcQaPersistenceAdapter(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),new ObjectMapper());
        scopes=new JdbcAuthorizedSpaceScope(jdbc);
        sources=new JdbcQaSourceAccessAdapter(jdbc,new ObjectMapper());
    }

    @AfterAll static void stop(){POSTGRES.stop();}

    @BeforeEach
    void seed(){
        for(String table:List.of("qa_model_call_audit","qa_answer","qa_evidence_snapshot","qa_request"))
            ownerJdbc.sql("DELETE FROM "+table).update();
        jdbc.sql("DELETE FROM acl_entry").update();
        jdbc.sql("UPDATE document SET current_published_version_id=NULL").update();
        for(String table:List.of("canonical_node","canonical_document","document_version","document",
                "knowledge_space","org_membership","org_unit","app_user"))
            jdbc.sql("DELETE FROM "+table).update();
        actor=UUID.randomUUID();request=UUID.randomUUID();evidence=UUID.randomUUID();
        uncitedEvidence=UUID.randomUUID();
        space=UUID.randomUUID();document=UUID.randomUUID();version=UUID.randomUUID();
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
                VALUES (:version,'node-1','PARAGRAPH','正文一',0),
                       (:version,'node-2','PARAGRAPH','正文二',1)
                """).param("version",version).update();
        jdbc.sql("""
                INSERT INTO acl_entry(id,space_id,scope,principal_type,principal_id,created_at,updated_at)
                VALUES (:id,:space,'SPACE','USER',:actor,:at,:at)
                """).param("id",UUID.randomUUID()).param("space",space).param("actor",actor)
                .param("at",OffsetDateTime.ofInstant(NOW.minusSeconds(1),ZoneOffset.UTC)).update();
        var state=persistence.create(request,actor,"e".repeat(64),NOW);
        var retrieving=persistence.transition(request,state.revision(),QaStatus.RETRIEVING,NOW).orElseThrow();
        var sanitizing=persistence.saveEvidence(request,retrieving.revision(),new EvidenceBundle(List.of(
                new QaEvidence(evidence,space,document,version,"node-1","制度","正文一",0.9,
                        new MarkdownLocator(List.of("第一章"),"node-1")),
                new QaEvidence(uncitedEvidence,space,document,version,"node-2","制度","正文二",0.8,
                        new MarkdownLocator(List.of("第二章"),"node-2"))),6),false,NOW).orElseThrow();
        var generating=persistence.transition(request,sanitizing.revision(),QaStatus.GENERATING,NOW).orElseThrow();
        var verifying=persistence.transition(request,generating.revision(),QaStatus.VERIFYING,NOW).orElseThrow();
        persistence.complete(request,verifying.revision(),new QaOutcome.Answered(
                request,"回答",List.of(new CitationClaim(UUID.randomUUID(),"结论",List.of(evidence))),false),NOW)
                .orElseThrow();
    }

    @Test
    void resolvesAllAuthorizedSpacesAndReauthorizesExactEvidenceSource(){
        assertThat(scopes.resolve(actor,NOW)).containsExactly(space);
        assertThat(sources.findAuthorized(request,uncitedEvidence,actor,NOW)).isEmpty();
        assertThat(sources.findAuthorized(request,evidence,actor,NOW)).get().satisfies(source->{
            assertThat(source.documentId()).isEqualTo(document);
            assertThat(source.versionId()).isEqualTo(version);
            assertThat(source.nodeId()).isEqualTo("node-1");
        });
    }

    @Test
    void hidesSourceForAnotherActorOrAfterAccessIsRevoked(){
        assertThat(sources.findAuthorized(request,evidence,UUID.randomUUID(),NOW)).isEmpty();
        jdbc.sql("UPDATE acl_entry SET revoked_at=:at WHERE space_id=:space")
                .param("at",OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC)).param("space",space).update();
        assertThat(scopes.resolve(actor,NOW)).isEmpty();
        assertThat(sources.findAuthorized(request,evidence,actor,NOW)).isEmpty();
    }

    @Test
    void hidesSourceWhenItsVersionIsNoLongerCurrentlyPublished(){
        jdbc.sql("UPDATE document SET current_published_version_id=NULL WHERE id=:document")
                .param("document",document).update();

        assertThat(sources.findAuthorized(request,evidence,actor,NOW)).isEmpty();
    }
}
