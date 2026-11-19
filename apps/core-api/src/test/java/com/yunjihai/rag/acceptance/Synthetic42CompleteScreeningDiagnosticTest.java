package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.RagApplication;
import com.yunjihai.rag.infrastructure.postgres.JdbcTableEvidenceSource;
import com.yunjihai.rag.qa.AuthorizedSpaceScopePort;
import com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector.ConflictKind;
import com.yunjihai.rag.retrieval.TrustedRetrievalService;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Isolated synthetic infrastructure only; no production data, models or credentials are logged. */
@EnabledIfEnvironmentVariable(named="RAG_SYNTH_COMPLETE_REPLAY",matches="true")
class Synthetic42CompleteScreeningDiagnosticTest {
    private static volatile com.yunjihai.rag.qa.model.GroundedAnswerPrompt diagnosticPrompt;
    private static volatile com.yunjihai.rag.qa.model.ModelGeneration diagnosticGeneration;

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class SyntheticModelDiagnosticConfiguration {
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanPostProcessor captureSyntheticModel() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if(!(bean instanceof com.yunjihai.rag.qa.model.ChatCompletionPort delegate)) return bean;
                    return new com.yunjihai.rag.qa.model.ChatCompletionPort() {
                        @Override public com.yunjihai.rag.qa.model.ModelCallDescriptor descriptor() { return delegate.descriptor(); }
                        @Override public com.yunjihai.rag.qa.model.ModelGeneration generate(com.yunjihai.rag.qa.model.GroundedAnswerPrompt prompt) {
                            var generation=delegate.generate(prompt);
                            diagnosticPrompt=prompt; diagnosticGeneration=generation;
                            return generation;
                        }
                    };
                }
            };
        }
    }
    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_MIGRATE",matches="true")
    void migratesOnlyTheVerifiedSyntheticDatabaseForPolicyAudit() throws Exception {
        String url="jdbc:postgresql://127.0.0.1:15432/rag_synthetic";
        String user="rag_synthetic_owner", password=required("RAG_SYNTH_DB_OWNER_PASSWORD");
        var jdbc=JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,user,password));
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).isEqualTo("rag_synthetic");
        assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                .query(Long.class).single()).isEqualTo(18);
        try(var hashes=getClass().getResourceAsStream("/synthetic42-source-sha256.json")) {
            var expected=new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    java.util.Objects.requireNonNull(hashes),new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>(){});
            assertThat(expected).hasSize(18).doesNotHaveDuplicates();
            assertThat(jdbc.sql("SELECT sha256 FROM active_blob_reservation ORDER BY sha256").query(String.class).list())
                    .containsExactlyElementsOf(expected);
        }
        var flyway=org.flywaydb.core.Flyway.configure().dataSource(url,user,password)
                .locations("classpath:db/migration").load();
        String current=flyway.info().current().getVersion().getVersion();
        assertThat(current).isIn("21","021","22","022","23","023","24","024","25","025","26","026");
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isIn("26","026");
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_SCOPED_TRIAL",matches="true")
    void springWiringDefaultsToStrictAndUsesAConfiguredLocalPolicyOnlyWhenEnabled() {
        for(boolean enabled:java.util.List.of(false,true)) {
            SyntheticScopedPolicyConfiguration.MODEL_CALLS.set(0);
            try(var context=startSyntheticContext(false,true,enabled)) {
                var jdbc=context.getBean(JdbcClient.class);
                assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                        .query(Long.class).single()).isEqualTo(18);
                assertThat(jdbc.sql("SELECT count(*) FROM retrieval_projection_event WHERE status <> 'SUCCEEDED'")
                        .query(Long.class).single()).isZero();
                UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name")
                        .param("name","模拟数据管理员").query(UUID.class).single();
                var qa=context.getBean(com.yunjihai.rag.qa.GroundedQaService.class);
                var backup=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,"星河项目的备份保留多久？"));
                if(enabled) {
                    assertThat(backup).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Answered.class,
                            answer->{assertThat(answer.answer()).isEqualTo("备份保留30天"); assertFixtureCitation(jdbc,answer);});
                    assertThat(SyntheticScopedPolicyConfiguration.MODEL_CALLS).hasValue(1);
                    assertThat(jdbc.sql("SELECT qa_policy_version FROM qa_model_call_audit WHERE request_id=:id")
                            .param("id",backup.requestId()).query(String.class).single()).isEqualTo("qa-scoped-synthetic-v1");
                    var budget=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,"星河项目研发预算究竟是多少？"));
                    assertThat(budget).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                            refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
                    assertThat(SyntheticScopedPolicyConfiguration.MODEL_CALLS).hasValue(1);
                } else {
                    assertThat(backup).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                            refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
                    assertThat(SyntheticScopedPolicyConfiguration.MODEL_CALLS).hasValue(0);
                    assertThat(jdbc.sql("SELECT count(*) FROM qa_model_call_audit WHERE request_id=:id")
                            .param("id",backup.requestId()).query(Long.class).single()).isZero();
                }
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_SCOPED_TRIAL",matches="true")
    void builtInBackupBudgetPolicyRefusesWhenFullScreeningContainsUnrecognizedCandidates() {
        SyntheticScopedPolicyConfiguration.MODEL_CALLS.set(0);
        try(var disabled=startSyntheticContext(false,false,false,true)) {
            var jdbc=disabled.getBean(JdbcClient.class);
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name").param("name","模拟数据管理员").query(UUID.class).single();
            var result=disabled.getBean(com.yunjihai.rag.qa.GroundedQaService.class).ask(
                    new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,"星河项目的备份保留多久？"));
            assertThat(result).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            assertThat(SyntheticScopedPolicyConfiguration.MODEL_CALLS).hasValue(0);
        }
        try(var context=startSyntheticContext(false,false,true,true)) {
            var jdbc=context.getBean(JdbcClient.class);
            assertThat(context.getBeansOfType(com.yunjihai.rag.qa.conflict.QuestionScopedConflictPolicy.class).values())
                    .singleElement().isInstanceOf(com.yunjihai.rag.qa.conflict.BackupBudgetQuestionScopedPolicy.class);
            assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                    .query(Long.class).single()).isEqualTo(18);
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name").param("name","模拟数据管理员").query(UUID.class).single();
            var qa=context.getBean(com.yunjihai.rag.qa.GroundedQaService.class);
            for(var question:java.util.List.of("星河项目的备份保留多久？","星河项目多久组织一次恢复演练？")) {
                var result=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,question));
                assertThat(result).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                        refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            }
            var budget=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,"星河项目研发预算究竟是多少？"));
            assertThat(budget).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            assertThat(SyntheticScopedPolicyConfiguration.MODEL_CALLS).hasValue(0);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_SCOPED_TRIAL",matches="true")
    void measuresStandaloneContextCoverageForTheFullAuthorizedCandidates() {
        try(var context=startSyntheticContext()) {
            var jdbc=context.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                    .query(Long.class).single()).isEqualTo(18);
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name").param("name","模拟数据管理员").query(UUID.class).single();
            var spaces=context.getBean(AuthorizedSpaceScopePort.class).resolve(actor,Instant.now());
            var retrieval=context.getBean(TrustedRetrievalService.class);
            var source=new JdbcTableEvidenceSource(jdbc);
            for(var question:java.util.List.of("星河项目的备份保留多久？","星河项目多久组织一次恢复演练？")) {
                var pack=retrieval.retrieve(new RetrievalQuery(UUID.randomUUID(),actor,spaces,question,Map.of(),10));
                int recognizedTable=0,recognizedSource=0,unknown=0;
                for(var item:pack.conflictScreeningEvidence()) {
                    if(item.locator() instanceof com.yunjihai.rag.retrieval.domain.TableLocator) {
                        if(source.hasStandaloneBudgetContext(item))recognizedTable++; else unknown++;
                    } else if(source.hasStandaloneBackupContext(item)) recognizedSource++; else unknown++;
                }
                assertThat(recognizedTable+recognizedSource+unknown).isEqualTo(pack.conflictScreeningEvidence().size());
                System.out.println("SYNTH_STANDALONE_CONTEXT id="+(question.contains("演练")?"Q06":"Q05")
                        +" candidates="+pack.conflictScreeningEvidence().size()+" standaloneBudget="+recognizedTable
                        +" standaloneSource="+recognizedSource+" unknown="+unknown);
            }
            for(var item:Map.of("backup","03_星河项目数据备份制度","budgetA","05_星河项目预算记录甲","budgetB","06_星河项目预算记录乙").entrySet()) {
                var shape=jdbc.sql("""
                        SELECT count(*),count(*) FILTER (WHERE n.kind='HEADING'),
                               count(*) FILTER (WHERE n.kind='PARAGRAPH'),count(*) FILTER (WHERE n.kind='TABLE'),
                               count(*) FILTER (WHERE n.kind='TABLE' AND n.body_text='研发预算')
                        FROM document d JOIN canonical_node n ON n.version_id=d.current_published_version_id
                        WHERE d.title=:title AND NOT d.archived
                        """).param("title",item.getValue()).query((rs,row)->new long[]{
                                rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)}).single();
                System.out.println("SYNTH_DOCUMENT_SHAPE id="+item.getKey()+" nodes="+shape[0]+" headings="+shape[1]
                        +" paragraphs="+shape[2]+" tables="+shape[3]+" exactBudgetBodies="+shape[4]);
                var tables=jdbc.sql("""
                        SELECT jsonb_array_length(n.table_cells),length(n.body_text),
                               n.body_text ~ '(备份|演练|期限|保留)',
                               EXISTS (SELECT 1 FROM jsonb_array_elements(n.table_cells) cell
                                       WHERE cell->>'text' ~ '(备份|演练|期限|保留)')
                        FROM document d JOIN canonical_node n ON n.version_id=d.current_published_version_id
                        WHERE d.title=:title AND NOT d.archived AND n.kind='TABLE' ORDER BY n.stable_order
                        """).param("title",item.getValue()).query((rs,row)->
                        "cells="+rs.getInt(1)+" bodyLength="+rs.getInt(2)+" topicInBody="+rs.getBoolean(3)+" topicInCells="+rs.getBoolean(4)).list();
                for(var table:tables)System.out.println("SYNTH_TABLE_SHAPE id="+item.getKey()+" "+table);
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_REVIEWED_TRIAL",matches="true")
    void reviewedSyntheticContextsPermitTwoAnswersAndInvalidateOnDocumentChange() throws Exception {
        SyntheticScopedPolicyConfiguration.MODEL_CALLS.set(0);
        try(var context=startSyntheticContext(false,false,true)) {
            var jdbc=context.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                    .query(Long.class).single()).isEqualTo(18);
            try(var hashes=getClass().getResourceAsStream("/synthetic42-source-sha256.json")) {
                var expected=new com.fasterxml.jackson.databind.ObjectMapper().readValue(java.util.Objects.requireNonNull(hashes),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>(){});
                assertThat(jdbc.sql("SELECT sha256 FROM active_blob_reservation ORDER BY sha256").query(String.class).list())
                        .containsExactlyElementsOf(expected);
            }
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name").param("name","模拟数据管理员").query(UUID.class).single();
            var qa=context.getBean(com.yunjihai.rag.qa.GroundedQaService.class);
            com.yunjihai.rag.qa.domain.QaOutcome first=null;
            for(var question:java.util.List.of("星河项目的备份保留多久？","星河项目多久组织一次恢复演练？","星河项目备份保留多长时间？")) {
                var result=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,question));
                assertThat(result).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Answered.class,answer->{
                    assertThat(answer.answer()).isEqualTo(question.contains("演练")?"每月组织一次恢复演练":"备份保留30天");
                    assertFixtureCitation(jdbc,answer);
                });
                assertThat(jdbc.sql("SELECT qa_policy_version FROM qa_model_call_audit WHERE request_id=:id")
                        .param("id",result.requestId()).query(String.class).single()).matches("qa-scoped-reviewed-v3-[0-9a-f]{32}");
                if(first==null)first=result;
            }
            assertThat(SyntheticScopedPolicyConfiguration.MODEL_CALLS).hasValue(3);
            var unapproved=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,"星河项目备份保留多少天？"));
            assertThat(unapproved).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            assertThat(SyntheticScopedPolicyConfiguration.MODEL_CALLS).hasValue(3);
            var budget=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,"星河项目研发预算究竟是多少？"));
            assertThat(budget).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            var spaces=context.getBean(AuthorizedSpaceScopePort.class).resolve(actor,Instant.now());
            var pack=context.getBean(TrustedRetrievalService.class).retrieve(new RetrievalQuery(UUID.randomUUID(),actor,spaces,
                    "星河项目的备份保留多久？",Map.of(),10));
            var changed=pack.conflictScreeningEvidence().stream().filter(e->!e.title().equals("03_星河项目数据备份制度")).findFirst().orElseThrow();
            try {
                jdbc.sql("UPDATE document SET title=:title WHERE id=:id").param("title",changed.title()+"-测试变更").param("id",changed.documentId()).update();
                assertThat(qa.find(first.requestId(),actor)).isEmpty();
                var refusal=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,"星河项目的备份保留多久？"));
                assertThat(refusal).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                        result->assertThat(result.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            } finally {
                jdbc.sql("UPDATE document SET title=:title WHERE id=:id").param("title",changed.title()).param("id",changed.documentId()).update();
            }
            assertThat(SyntheticScopedPolicyConfiguration.MODEL_CALLS).hasValue(3);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_REVIEWED_TRIAL",matches="true")
    void reviewedQuestionCompletesMissingAuthorizedDocumentNodeLocally() throws Exception {
        SyntheticScopedPolicyConfiguration.MODEL_CALLS.set(0);
        try(var context=startSyntheticContext(false,false,true)) {
            var jdbc=context.getBean(JdbcClient.class);
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name").param("name","模拟数据管理员").query(UUID.class).single();
            var actual=context.getBean(TrustedRetrievalService.class);
            var removed=new java.util.concurrent.atomic.AtomicReference<String>();
            com.yunjihai.rag.retrieval.RetrievalService incomplete=query->{
                var pack=actual.retrieve(query);
                var candidate=pack.conflictScreeningEvidence().stream()
                        .filter(e->e.title().equals("03_星河项目数据备份制度"))
                        .filter(e->!e.excerpt().contains("30"))
                        .findFirst().orElseThrow();
                removed.set(candidate.nodeId());
                var filtered=pack.conflictScreeningEvidence().stream().filter(e->!(e.documentId().equals(candidate.documentId()) && e.nodeId().equals(candidate.nodeId()))).toList();
                var selected=pack.evidence().stream().filter(e->!(e.documentId().equals(candidate.documentId()) && e.nodeId().equals(candidate.nodeId()))).toList();
                return new com.yunjihai.rag.retrieval.domain.EvidencePack(pack.requestId(),selected,pack.degraded(),
                        pack.degradationReasons(),pack.stats(),pack.completedAt(),filtered);
            };
            var qa=new com.yunjihai.rag.qa.GroundedQaService(context.getBean(com.yunjihai.rag.qa.AuthorizedSpaceScopePort.class),incomplete,
                    context.getBean(com.yunjihai.rag.qa.QaPersistencePort.class),context.getBean(com.yunjihai.rag.qa.ModelCallAuditPort.class),
                    context.getBean(com.yunjihai.rag.qa.QuestionDigestPort.class),context.getBean(com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer.class),
                    context.getBean(com.yunjihai.rag.qa.model.ChatCompletionPort.class),context.getBean(com.yunjihai.rag.qa.conflict.EvidenceConflictDetector.class),
                    context.getBean(com.yunjihai.rag.qa.CitationSupportVerifier.class),context.getBean(com.yunjihai.rag.qa.QaPolicy.class),
                    context.getBean(com.yunjihai.rag.qa.QaEvidenceAuthorizationPort.class),context.getBean(java.time.Clock.class),
                    context.getBean(com.yunjihai.rag.qa.QaMetrics.class),context.getBean(com.yunjihai.rag.qa.evaluation.EvaluationTraceRegistry.class),
                    context.getBean(com.yunjihai.rag.qa.conflict.QuestionScopedConflictPolicy.class));
            var result=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,"星河项目的备份保留多久？"));
            assertThat(removed.get()).isNotBlank();
            assertThat(result).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Answered.class,
                    answered->assertThat(answered.answer()).isEqualTo("备份保留30天"));
            assertThat(SyntheticScopedPolicyConfiguration.MODEL_CALLS).hasValue(1);
            assertThat(qa.find(result.requestId(),actor)).isPresent();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_REAL_MODEL",matches="true")
    void reviewedSyntheticContextsWithRealModelRemainGrounded() throws Exception {
        if(!"true".equals(System.getenv("RAG_SYNTH_REVIEWED_TRIAL")))throw new IllegalStateException("REVIEWED_TRIAL_NOT_ENABLED");
        try(var context=startSyntheticContext(true,false,true)) {
            var jdbc=context.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                    .query(Long.class).single()).isEqualTo(18);
            try(var hashes=getClass().getResourceAsStream("/synthetic42-source-sha256.json")) {
                var expected=new com.fasterxml.jackson.databind.ObjectMapper().readValue(java.util.Objects.requireNonNull(hashes),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>(){});
                assertThat(jdbc.sql("SELECT sha256 FROM active_blob_reservation ORDER BY sha256").query(String.class).list())
                        .containsExactlyElementsOf(expected);
            }
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name").param("name","模拟数据管理员").query(UUID.class).single();
            var spaces=context.getBean(AuthorizedSpaceScopePort.class).resolve(actor,Instant.now());
            var retrieval=context.getBean(TrustedRetrievalService.class);
            var source=new JdbcTableEvidenceSource(jdbc);
            for(var question:java.util.List.of("星河项目的备份保留多久？","星河项目多久组织一次恢复演练？")) {
                var pack=retrieval.retrieve(new RetrievalQuery(UUID.randomUUID(),actor,spaces,question,Map.of(),10));
                assertThat(new DeterministicEvidenceConflictDetector(source).conflictKind(pack.conflictScreeningEvidence()))
                        .isNotEqualTo(ConflictKind.NONE);
                assertThat(pack.conflictScreeningEvidence()).allSatisfy(candidate ->
                        assertThat(source.verifiedContextFingerprint(candidate)).isPresent());
            }
            var qa=context.getBean(com.yunjihai.rag.qa.GroundedQaService.class);
            int answered=0;
            int repeats=Integer.parseInt(System.getenv().getOrDefault("RAG_SYNTH_REAL_REPEATS","1"));
            if(repeats<1 || repeats>3)throw new IllegalStateException("INVALID_SYNTHETIC_REPEAT_COUNT");
            for(int repeat=0;repeat<repeats;repeat++) for(var question:java.util.List.of("星河项目的备份保留多久？","星河项目多久组织一次恢复演练？")) {
                diagnosticPrompt=null; diagnosticGeneration=null;
                var result=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,question));
                assertThat(diagnosticPrompt).isNotNull();
                assertThat(diagnosticGeneration).isNotNull();
                var candidate=diagnosticGeneration.answer();
                var exact=new com.yunjihai.rag.qa.ExactQuoteCitationSupportVerifier().isSupported(candidate,diagnosticPrompt.evidence());
                var review=context.getBean(com.yunjihai.rag.qa.conflict.QuestionScopedConflictPolicy.class)
                        .verifyAnswer(diagnosticPrompt.question(),candidate,diagnosticPrompt.evidence());
                System.out.println("SYNTH_REVIEWED_GATES id="+(question.contains("演练")?"Q06":"Q05")
                        +" exact="+exact+" reviewed="+review+" claims="+candidate.claims().size());
                if(result instanceof com.yunjihai.rag.qa.domain.QaOutcome.Answered answer) {
                    assertFixtureCitation(jdbc,answer);
                    assertThat(answer.answer()).contains(question.contains("演练")?"每月":"30天");
                    answered++;
                } else {
                    assertThat(result).isInstanceOf(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class);
                }
                var audit=jdbc.sql("SELECT input_tokens,output_tokens,result_code FROM qa_model_call_audit WHERE request_id=:id")
                        .param("id",result.requestId()).query((rs,row)->new Object[]{rs.getInt(1),rs.getInt(2),rs.getString(3)}).optional();
                assertThat(audit).isPresent();
                var usage=audit.orElseThrow();
                System.out.println("SYNTH_REVIEWED_REAL run="+(repeat+1)+" id="+(question.contains("演练")?"Q06":"Q05")+" status="+result.status()
                        +" inputTokens="+usage[0]+" outputTokens="+usage[1]+" modelResult="+usage[2]);
            }
            System.out.println("SYNTH_REVIEWED_REAL_SUMMARY answered="+answered+" total="+(repeats*2));
        }
    }

    /** Test fixture only: entries are simulated review, not business approval. */
    static class ReviewedSyntheticContextConfiguration {
        static byte[] signedManifest(
                JdbcClient jdbc,com.yunjihai.rag.qa.QuestionDigestPort digests) throws Exception {
            var input=java.util.Objects.requireNonNull(ReviewedSyntheticContextConfiguration.class.getResourceAsStream("/synthetic42-reviewed-contexts.json"));
            var fixture=new com.fasterxml.jackson.databind.ObjectMapper().readTree(input).path("文档");
            assertThat(fixture.size()).isEqualTo(18);
            var documents=new java.util.ArrayList<com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.DocumentReview>();
            UUID sourceId=null;
            var fields=fixture.fields();
            while(fields.hasNext()) {
                var item=fields.next();
                var row=jdbc.sql("SELECT id,qa_document_context_fingerprint(id,current_published_version_id) FROM document WHERE title=:title AND NOT archived")
                        .param("title",item.getKey()).query((rs,index)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2)}).single();
                assertThat(row[1]).isEqualTo(item.getValue().asText());
                boolean relevant=item.getKey().equals("03_星河项目数据备份制度");
                UUID id=(UUID)row[0]; if(relevant)sourceId=id;
                documents.add(new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.DocumentReview(id,item.getValue().asText(),
                        relevant?com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.Relation.RELEVANT:
                                com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.Relation.UNRELATED));
            }
            UUID approvedSource=java.util.Objects.requireNonNull(sourceId);
            var approvedNode=jdbc.sql("""
                    SELECT n.version_id,n.node_id,n.body_text FROM document d JOIN canonical_node n ON n.version_id=d.current_published_version_id
                    WHERE d.id=:id AND n.body_text LIKE '%备份保留30天%'
                    """).param("id",approvedSource).query((rs,row)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)}).single();
            var questions=new java.util.ArrayList<com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.QuestionReview>();
            for(var q:java.util.List.of("星河项目的备份保留多久?","星河项目多久组织一次恢复演练?","星河项目备份保留多长时间?")) {
                boolean drill=q.contains("演练");
                String sourceBody=(String)approvedNode[2];
                String[] sentences=sourceBody.split("。");
                assertThat(sentences).hasSize(2);
                String sentence=sentences[drill?1:0];
                String phrase=drill?"每月组织一次恢复演练":"备份保留30天";
                String shortValue=drill?"每月":"30天";
                var claims=new java.util.ArrayList<com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.ClaimReview>();
                for(String claim:java.util.Set.of(phrase,phrase+"。",shortValue,shortValue+"。",sentence,sentence+"。")) {
                    for(String quote:java.util.Set.of(sourceBody,sentence,sentence+"。",phrase,phrase+"。",shortValue,shortValue+"。")) {
                        if(sourceBody.contains(quote) && quote.contains(claim))claims.add(
                                new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.ClaimReview(approvedSource,
                                        (UUID)approvedNode[0],(String)approvedNode[1],digests.digest("claim:"+claim),digests.digest("quote:"+quote)));
                    }
                }
                questions.add(new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.QuestionReview(
                        digests.digest("question:"+q),UUID.randomUUID(),true,documents,
                        claims));
            }
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            var manifest=new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.Manifest(questions);
            String payload=java.util.Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(manifest));
            var signed=new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.SignedManifest(payload,digests.digest("manifest:"+payload));
            return mapper.writeValueAsBytes(signed);
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class SyntheticChatOnlyConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        com.yunjihai.rag.qa.model.ChatCompletionPort syntheticScopedChat() {
            return new SyntheticScopedPolicyConfiguration().syntheticScopedChat();
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class SyntheticScopedPolicyConfiguration {
        static final java.util.concurrent.atomic.AtomicInteger MODEL_CALLS=new java.util.concurrent.atomic.AtomicInteger();
        @org.springframework.context.annotation.Bean
        com.yunjihai.rag.qa.conflict.QuestionScopedConflictPolicy syntheticScopedPolicy(JdbcClient jdbc) {
            var tableSource=new JdbcTableEvidenceSource(jdbc);
            return new com.yunjihai.rag.qa.conflict.QuestionScopedConflictPolicy() {
                @Override public String version(){return "synthetic-v1";}
                @Override public Scope classify(com.yunjihai.rag.qa.domain.QaQuestion question,
                        DeterministicEvidenceConflictDetector.ConflictPair pair,java.util.List<com.yunjihai.rag.retrieval.domain.Evidence> items) {
                    var byId=items.stream().collect(Collectors.toMap(item->item.evidenceId(),item->item));
                    return QuestionScopedConflictShadow.assess(question.value(),pair,byId,tableSource)
                            ==QuestionScopedConflictShadow.Scope.EXPLICITLY_UNRELATED ? Scope.UNRELATED : Scope.UNKNOWN;
                }
                @Override public AnswerRelevance verifyAnswer(com.yunjihai.rag.qa.domain.QaQuestion question,
                        com.yunjihai.rag.qa.domain.CandidateAnswer answer,com.yunjihai.rag.qa.domain.EvidenceBundle evidence) {
                    return QuestionScopedAnswerShadow.supports(question.value(),answer,evidence)
                            ? AnswerRelevance.RELEVANT : AnswerRelevance.UNKNOWN;
                }
            };
        }
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.yunjihai.rag.qa.model.ChatCompletionPort syntheticScopedChat() {
            return new com.yunjihai.rag.qa.model.ChatCompletionPort() {
                @Override public com.yunjihai.rag.qa.model.ModelCallDescriptor descriptor() {
                    return new com.yunjihai.rag.qa.model.ModelCallDescriptor("synthetic","local-test","fake","synthetic-v1");
                }
                @Override public com.yunjihai.rag.qa.model.ModelGeneration generate(com.yunjihai.rag.qa.model.GroundedAnswerPrompt prompt) {
                    MODEL_CALLS.incrementAndGet();
                    var evidence=prompt.evidence().evidence().stream().filter(item->item.title().equals("03_星河项目数据备份制度")
                            && item.excerpt().contains("备份保留30天")).findFirst().orElseThrow();
                    var support=new com.yunjihai.rag.qa.domain.EvidenceSupport(evidence.evidenceId(),evidence.excerpt());
                    String value=prompt.question().value().contains("恢复演练")?"每月组织一次恢复演练":"备份保留30天";
                    var claim=new com.yunjihai.rag.qa.domain.GroundedClaim(UUID.randomUUID(),value,java.util.List.of(support));
                    var answer=new com.yunjihai.rag.qa.domain.CandidateAnswer(com.yunjihai.rag.qa.domain.CandidateAnswer.Decision.ANSWER,
                            value,java.util.List.of(claim));
                    return new com.yunjihai.rag.qa.model.ModelGeneration(answer,
                            new com.yunjihai.rag.qa.model.ModelUsage(0,0,java.time.Duration.ZERO),"synthetic",0);
                }
            };
        }
    }

    @Test
    void selectedDocumentRetrievalExcludesOtherDocumentsAndRechecksRevokedAcl() {
        try(var context=startSyntheticContext()) {
            var jdbc=context.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                    .query(Long.class).single()).isEqualTo(18);
            assertThat(jdbc.sql("SELECT count(*) FROM retrieval_projection_event WHERE status <> 'SUCCEEDED'")
                    .query(Long.class).single()).isZero();
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name")
                    .param("name","模拟数据管理员").query(UUID.class).single();
            UUID space=jdbc.sql("SELECT id FROM knowledge_space WHERE name=:name")
                    .param("name","模拟资料评测空间").query(UUID.class).single();
            UUID first=jdbc.sql("SELECT id FROM document WHERE title=:title AND space_id=:space AND NOT archived")
                    .param("title","05_星河项目预算记录甲").param("space",space).query(UUID.class).single();
            UUID second=jdbc.sql("SELECT id FROM document WHERE title=:title AND space_id=:space AND NOT archived")
                    .param("title","06_星河项目预算记录乙").param("space",space).query(UUID.class).single();
            var retrieval=context.getBean(TrustedRetrievalService.class);
            for(var document:java.util.List.of(first,second)) {
                var pack=retrieval.retrieve(new RetrievalQuery(UUID.randomUUID(),actor,java.util.Set.of(space),
                        "星河项目研发预算",Map.of("documentId",document.toString()),10));
                assertThat(pack.evidence()).isNotEmpty().allSatisfy(item->assertThat(item.documentId()).isEqualTo(document));
                assertThat(pack.conflictScreeningEvidence()).isNotEmpty().allSatisfy(item->assertThat(item.documentId()).isEqualTo(document));
            }
            UUID temporaryAcl=UUID.randomUUID();
            int inserted=jdbc.sql("""
                    INSERT INTO acl_entry(id,space_id,document_id,scope,principal_type,principal_id,revoked_at)
                    SELECT :id,:space,:document,'DOCUMENT','USER',:actor,now()
                    WHERE NOT EXISTS (SELECT 1 FROM acl_entry WHERE scope='DOCUMENT' AND document_id=:document)
                    """).param("id",temporaryAcl).param("space",space).param("document",first).param("actor",actor).update();
            assertThat(inserted).isEqualTo(1);
            try {
                var denied=retrieval.retrieve(new RetrievalQuery(UUID.randomUUID(),actor,java.util.Set.of(space),
                        "星河项目研发预算",Map.of("documentId",first.toString()),10));
                assertThat(denied.evidence()).isEmpty();
                assertThat(denied.conflictScreeningEvidence()).isEmpty();
            } finally {
                assertThat(jdbc.sql("DELETE FROM acl_entry WHERE id=:id AND scope='DOCUMENT' AND revoked_at IS NOT NULL")
                        .param("id",temporaryAcl).update()).isEqualTo(1);
            }
        }
    }

    @Test
    void observesCompleteReauthorizedScreeningCandidatesWithoutChangingQaPolicy() throws Exception {
        try (var context=startSyntheticContext()) {
            var jdbc=context.getBean(JdbcClient.class);
            long documents=jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                    .query(Long.class).single();
            assertThat(documents).as("complete synthetic document set must be published").isEqualTo(18);
            assertThat(jdbc.sql("SELECT count(*) FROM retrieval_projection_event WHERE status <> 'SUCCEEDED'")
                    .query(Long.class).single()).as("synthetic projection must be complete").isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM knowledge_space WHERE name=:name")
                    .param("name","模拟资料评测空间").query(Long.class).single()).isEqualTo(1);
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name")
                    .param("name","模拟数据管理员").query(UUID.class).single();
            var spaces=context.getBean(AuthorizedSpaceScopePort.class).resolve(actor,Instant.now());
            assertThat(spaces).hasSize(1);
            var retrieval=context.getBean(TrustedRetrievalService.class);
            var source=new JdbcTableEvidenceSource(jdbc);
            var detector=new DeterministicEvidenceConflictDetector(source);
            int total=0,expectedAnswers=0,answerConflicts=0,allUnrelated=0,screeningFailures=0,actualConflictRefusals=0;
            int sourceSpecific=0,sourceSpecificSelected=0,sourceSpecificFieldInCells=0,sourceSpecificFieldInExcerpt=0;
            var qa=context.getBean(com.yunjihai.rag.qa.GroundedQaService.class);
            try(var input=getClass().getResourceAsStream("/synthetic42-questions.json")) {
                var dataset=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.util.Objects.requireNonNull(input));
                assertThat(dataset.path("cases")).hasSize(42);
                for(var item:dataset.path("cases")) {
                    total++;
                    String id=item.path("id").asText(), question=item.path("question").asText();
                    boolean expectedAnswer="可回答".equals(item.path("expectedBehavior").asText());
                    if(expectedAnswer)expectedAnswers++;
                    var pack=retrieval.retrieve(new RetrievalQuery(UUID.randomUUID(),actor,spaces,question,Map.of(),10));
                    var candidates=pack.conflictScreeningEvidence();
                    var byId=candidates.stream().collect(Collectors.toMap(e->e.evidenceId(),e->e));
                    boolean strictConflict;
                    java.util.List<DeterministicEvidenceConflictDetector.ConflictPair> pairs;
                    try {
                        strictConflict=detector.conflictKind(candidates)!=ConflictKind.NONE;
                        pairs=detector.findConflictingPairs(candidates);
                    } catch (RuntimeException failure) {
                        screeningFailures++;
                        System.out.println("SYNTH42_COMPLETE id="+id+" screening=UNAVAILABLE");
                        continue;
                    }
                    assertThat(pairs.isEmpty()).isEqualTo(!strictConflict);
                    int explicit=0;
                    for(var pair:pairs) {
                        assertThat(byId).containsKeys(pair.firstEvidenceId(),pair.secondEvidenceId());
                        if(QuestionScopedConflictShadow.assess(question,pair,byId,source)
                                ==QuestionScopedConflictShadow.Scope.EXPLICITLY_UNRELATED)explicit++;
                    }
                    int unknown=pairs.size()-explicit;
                    var namedTargets=pairs.stream().map(pair->SourceSpecificConflictDiagnostic.target(question,pair,byId)).toList();
                    boolean namedRecord=!pairs.isEmpty() && namedTargets.stream().allMatch(java.util.Optional::isPresent)
                            && namedTargets.stream().map(java.util.Optional::orElseThrow).distinct().count()==1;
                    boolean namedSelected=namedRecord && SourceSpecificConflictDiagnostic.isSelected(
                            byId.get(namedTargets.getFirst().orElseThrow()),pack.evidence());
                    if(expectedAnswer && namedRecord)sourceSpecific++;
                    if(expectedAnswer && namedSelected)sourceSpecificSelected++;
                    String expectedField=switch(id) {
                        case "Q09" -> "100";
                        case "Q11" -> "120";
                        case "Q10","Q12" -> "未注明";
                        default -> null;
                    };
                    boolean fieldInCells=false,fieldInExcerpt=false;
                    if(expectedField!=null && namedSelected) {
                        var target=byId.get(namedTargets.getFirst().orElseThrow());
                        fieldInCells=source.load(target).stream().anyMatch(cell->expectedField.equals(cell.text().strip()));
                        fieldInExcerpt=target.excerpt().contains(expectedField);
                        if(fieldInCells)sourceSpecificFieldInCells++;
                        if(fieldInExcerpt)sourceSpecificFieldInExcerpt++;
                    }
                    boolean answerSpan=pack.evidence().stream().anyMatch(e->e.title().equals("03_星河项目数据备份制度")
                            && (id.equals("Q05") && e.excerpt().contains("备份保留30天")
                            || id.equals("Q06") && e.excerpt().contains("每月") && e.excerpt().contains("组织一次恢复演练")));
                    if(expectedAnswer&&strictConflict)answerConflicts++;
                    if(expectedAnswer&&strictConflict&&unknown==0)allUnrelated++;
                    if(id.equals("Q37"))assertThat(explicit).isZero();
                    var outcome=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,question));
                    boolean actualConflict=outcome instanceof com.yunjihai.rag.qa.domain.QaOutcome.Refused refused
                            && refused.reason()==com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE;
                    if(expectedAnswer && actualConflict)actualConflictRefusals++;
                    if(id.equals("Q05")||id.equals("Q06")||id.equals("Q37"))assertThat(actualConflict).isTrue();
                    String spanResult=id.equals("Q05")||id.equals("Q06")?Boolean.toString(answerSpan):"NOT_MEASURED";
                    System.out.println("SYNTH42_COMPLETE id="+id+" expectedAnswer="+expectedAnswer
                            +" selected="+pack.evidence().size()+" authorizedCandidates="+candidates.size()
                            +" conflictPairs="+pairs.size()+" explicitUnrelated="+explicit+" unknown="+unknown
                            +" sourceSpecific="+namedRecord+" targetSelected="+namedSelected
                            +" targetFieldInCells="+fieldInCells+" targetFieldInExcerpt="+fieldInExcerpt
                            +" selectedKnownSpan="+spanResult+" actualStatus="+outcome.status()
                            +" actualConflictRefusal="+actualConflict);
                }
            }
            assertThat(total).isEqualTo(42);
            assertThat(expectedAnswers).isEqualTo(38);
            System.out.println("SYNTH42_COMPLETE_SUMMARY questions="+total+" expectedAnswers="+expectedAnswers
                    +" expectedAnswersWithConflict="+answerConflicts+" pairOnlyPotential="+allUnrelated
                    +" sourceSpecific="+sourceSpecific+" sourceSpecificSelected="+sourceSpecificSelected
                    +" targetFieldInCells="+sourceSpecificFieldInCells+" targetFieldInExcerpt="+sourceSpecificFieldInExcerpt
                    +" screeningUnavailable="+screeningFailures+" actualAnswerConflictRefusals="+actualConflictRefusals);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_SPAN_AUDIT",matches="true")
    void comparesReferenceSourceLiteralSpansOnCurrentAuthorizedRetrieval() throws Exception {
        try(var context=startSyntheticContext()) {
            var jdbc=context.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                    .query(Long.class).single()).isEqualTo(18);
            try(var hashes=getClass().getResourceAsStream("/synthetic42-source-sha256.json")) {
                var expected=new com.fasterxml.jackson.databind.ObjectMapper().readValue(java.util.Objects.requireNonNull(hashes),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>(){});
                assertThat(jdbc.sql("SELECT sha256 FROM active_blob_reservation ORDER BY sha256").query(String.class).list())
                        .containsExactlyElementsOf(expected);
            }
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name").param("name","模拟数据管理员").query(UUID.class).single();
            var spaces=context.getBean(AuthorizedSpaceScopePort.class).resolve(actor,Instant.now());
            var retrieval=context.getBean(TrustedRetrievalService.class);
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            var questions=new java.util.HashMap<String,String>();
            try(var input=getClass().getResourceAsStream("/synthetic42-questions.json")) {
                for(var item:mapper.readTree(java.util.Objects.requireNonNull(input)).path("cases"))
                    questions.put(item.path("id").asText(),item.path("question").asText());
            }
            int count=0,found=0;
            try(var input=getClass().getResourceAsStream("/synthetic42-literal-answer-spans.json")) {
                var cases=mapper.readTree(java.util.Objects.requireNonNull(input)).path("cases");
                assertThat(cases.size()).isEqualTo(37);
                for(var item:cases) {
                    String id=item.path("id").asText(),title=item.path("title").asText();
                    String answer=item.path("answer").asText().replaceFirst("[。！？，；]+$", "");
                    assertThat(questions).containsKey(id);
                    var pack=retrieval.retrieve(new RetrievalQuery(UUID.randomUUID(),actor,spaces,questions.get(id),Map.of(),10));
                    boolean matched=pack.evidence().stream().anyMatch(e->e.title().equals(title) && e.excerpt().contains(answer));
                    if(matched)found++;
                    count++;
                    System.out.println("SYNTH_SPAN id="+id+" hit="+matched+" screened="+pack.conflictScreeningEvidence().size());
                }
            }
            System.out.println("SYNTH_SPAN_SUMMARY cases="+count+" hits="+found);
            assertThat(count).isEqualTo(37);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_RECORD_COMPLETENESS",matches="true")
    void measuresWhetherNamedRecordQuestionsIncludeEveryCanonicalNode() {
        try(var context=startSyntheticContext()) {
            var jdbc=context.getBean(JdbcClient.class);
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name").param("name","模拟数据管理员").query(UUID.class).single();
            var spaces=context.getBean(AuthorizedSpaceScopePort.class).resolve(actor,Instant.now());
            var retrieval=context.getBean(TrustedRetrievalService.class);
            String[][] cases={{"Q09","星河项目预算记录甲记载多少？","05_星河项目预算记录甲"},
                    {"Q10","星河预算记录甲是否注明审批日期？","05_星河项目预算记录甲"},
                    {"Q11","星河项目预算记录乙记载多少？","06_星河项目预算记录乙"},
                    {"Q12","星河预算记录乙是否注明版本号？","06_星河项目预算记录乙"}};
            for(var item:cases) {
                var doc=jdbc.sql("SELECT id,current_published_version_id FROM document WHERE title=:title AND NOT archived")
                        .param("title",item[2]).query((rs,row)->new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)}).single();
                var nodes=jdbc.sql("SELECT node_id FROM canonical_node WHERE version_id=:version ORDER BY stable_order")
                        .param("version",doc[1]).query(String.class).list();
                var pack=retrieval.retrieve(new RetrievalQuery(UUID.randomUUID(),actor,spaces,item[1],Map.of(),10));
                var selected=pack.evidence().stream().filter(e->e.documentId().equals(doc[0]) && e.versionId().equals(doc[1]))
                        .map(e->e.nodeId()).collect(Collectors.toSet());
                var screened=pack.conflictScreeningEvidence().stream().filter(e->e.documentId().equals(doc[0]) && e.versionId().equals(doc[1]))
                        .map(e->e.nodeId()).collect(Collectors.toSet());
                assertThat(nodes).isNotEmpty();
                System.out.println("SYNTH_RECORD_COMPLETENESS id="+item[0]+" nodes="+nodes.size()
                        +" selected="+selected.size()+" screened="+screened.size()
                        +" selectedComplete="+selected.containsAll(nodes)+" screenedComplete="+screened.containsAll(nodes));
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_SCOPED_TRIAL",matches="true")
    void isolatedTrialAnswersOnlyWithKnownRelevantEvidenceAndRefusesUncertainty() throws Exception {
        try(var context=startSyntheticContext()) {
            var jdbc=context.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                    .query(Long.class).single()).isEqualTo(18);
            assertThat(jdbc.sql("SELECT count(*) FROM retrieval_projection_event WHERE status <> 'SUCCEEDED'")
                    .query(Long.class).single()).isZero();
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name")
                    .param("name","模拟数据管理员").query(UUID.class).single();
            var qa05=QuestionScopedQaTrial.run(context,actor,"星河项目的备份保留多久？",QuestionScopedQaTrial.Mode.GOOD);
            assertThat(qa05.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Answered.class,
                    answer->{ assertThat(answer.answer()).isEqualTo("备份保留30天"); assertFixtureCitation(jdbc,answer); });
            assertThat(qa05.modelCalls()).isEqualTo(1);
            assertThat(qa05.exactQuoteSupported()).isTrue();
            var qa06=QuestionScopedQaTrial.run(context,actor,"星河项目多久组织一次恢复演练？",QuestionScopedQaTrial.Mode.GOOD);
            assertThat(qa06.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Answered.class,
                    answer->{ assertThat(answer.answer()).isEqualTo("每月组织一次恢复演练"); assertFixtureCitation(jdbc,answer); });
            assertThat(qa06.modelCalls()).isEqualTo(1);
            assertThat(qa06.exactQuoteSupported()).isTrue();
            var budget=QuestionScopedQaTrial.run(context,actor,"星河项目研发预算究竟是多少？",QuestionScopedQaTrial.Mode.GOOD);
            assertThat(budget.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            assertThat(budget.modelCalls()).isZero();
            var irrelevant=QuestionScopedQaTrial.run(context,actor,"星河项目的备份保留多久？",QuestionScopedQaTrial.Mode.UNRELATED_ANSWER);
            assertThat(irrelevant.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.UNSUPPORTED_CLAIM));
            assertThat(irrelevant.modelCalls()).isEqualTo(1);
            assertThat(irrelevant.exactQuoteSupported()).as("无关预算回答虽能通过逐字引用，仍要被相关性检查拒绝").isTrue();
            var unknown=QuestionScopedQaTrial.run(context,actor,"星河项目的备份保留多久？",QuestionScopedQaTrial.Mode.EXTRA_CONFLICT);
            assertThat(unknown.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            assertThat(unknown.modelCalls()).isZero();
            var unavailable=QuestionScopedQaTrial.run(context,actor,"星河项目的备份保留多久？",QuestionScopedQaTrial.Mode.TABLE_FAILURE);
            assertThat(unavailable.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            assertThat(unavailable.modelCalls()).isZero();
            var revoked=QuestionScopedQaTrial.run(context,actor,"星河项目的备份保留多久？",QuestionScopedQaTrial.Mode.FINAL_REVOKED);
            assertThat(revoked.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.NO_AUTHORIZED_SPACE));
            assertThat(revoked.modelCalls()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM qa_answer WHERE request_id=:id")
                    .param("id",revoked.outcome().requestId()).query(Long.class).single()).isZero();
        }
    }

    private static void assertFixtureCitation(JdbcClient jdbc,com.yunjihai.rag.qa.domain.QaOutcome.Answered answer) {
        assertFixtureCitation(jdbc,answer,"03_星河项目数据备份制度");
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_SCOPED_TRIAL",matches="true")
    void namedRecordTrialRequiresCompleteTableCorrectCitationAndFinalAuthorization() {
        try(var context=startSyntheticContext()) {
            var jdbc=context.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                    .query(Long.class).single()).isEqualTo(18);
            assertThat(jdbc.sql("SELECT count(*) FROM retrieval_projection_event WHERE status <> 'SUCCEEDED'")
                    .query(Long.class).single()).isZero();
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name")
                    .param("name","模拟数据管理员").query(UUID.class).single();
            String[][] cases={
                    {"星河项目预算记录甲记载多少？","05_星河项目预算记录甲","100"},
                    {"星河预算记录甲是否注明审批日期？","05_星河项目预算记录甲","未注明"},
                    {"星河项目预算记录乙记载多少？","06_星河项目预算记录乙","120"},
                    {"星河预算记录乙是否注明版本号？","06_星河项目预算记录乙","未注明"}};
            for(var item:cases) {
                var result=SourceSpecificQaTrial.run(context,actor,item[0],SourceSpecificQaTrial.Mode.GOOD);
                assertThat(result.outcome()).as(item[0]).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Answered.class,
                        answer->{assertThat(answer.answer()).contains(item[2],"金额（万元）","审批日期及版本");
                            assertFixtureCitation(jdbc,answer,item[1]);});
                assertThat(result.modelCalls()).isEqualTo(1);
                assertThat(result.exactQuoteSupported()).isTrue();
            }
            String question=cases[0][0];
            for(var mode:java.util.List.of(SourceSpecificQaTrial.Mode.WRONG_RECORD,SourceSpecificQaTrial.Mode.PARTIAL)) {
                var result=SourceSpecificQaTrial.run(context,actor,question,mode);
                assertThat(result.exactQuoteSupported()).isTrue();
                assertThat(result.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                        refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.UNSUPPORTED_CLAIM));
            }
            for(var mode:java.util.List.of(SourceSpecificQaTrial.Mode.EXTRA_CONFLICT,SourceSpecificQaTrial.Mode.TABLE_FAILURE)) {
                var result=SourceSpecificQaTrial.run(context,actor,question,mode);
                assertThat(result.modelCalls()).isZero();
                assertThat(result.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                        refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            }
            var budget=SourceSpecificQaTrial.run(context,actor,"星河项目研发预算究竟是多少？",SourceSpecificQaTrial.Mode.GOOD);
            assertThat(budget.modelCalls()).isZero();
            assertThat(budget.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.CONFLICTING_EVIDENCE));
            var revoked=SourceSpecificQaTrial.run(context,actor,question,SourceSpecificQaTrial.Mode.FINAL_REVOKED);
            assertThat(revoked.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.NO_AUTHORIZED_SPACE));
            assertThat(jdbc.sql("SELECT count(*) FROM qa_answer WHERE request_id=:id")
                    .param("id",revoked.outcome().requestId()).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT enabled FROM app_user WHERE id=:id").param("id",actor).query(Boolean.class).single()).isTrue();
            var aclRevoked=SourceSpecificQaTrial.run(context,actor,question,SourceSpecificQaTrial.Mode.REAL_SPACE_REVOKED);
            assertThat(aclRevoked.modelCalls()).isEqualTo(1);
            assertThat(aclRevoked.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.NO_AUTHORIZED_SPACE));
            assertThat(jdbc.sql("SELECT count(*) FROM qa_answer WHERE request_id=:id")
                    .param("id",aclRevoked.outcome().requestId()).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM acl_entry WHERE scope='SPACE' AND principal_type='USER' AND principal_id=:id AND revoked_at IS NULL")
                    .param("id",actor).query(Long.class).single()).isEqualTo(1);
            long beforeDocumentAcls=jdbc.sql("SELECT count(*) FROM acl_entry WHERE scope='DOCUMENT'").query(Long.class).single();
            var documentRevoked=SourceSpecificQaTrial.run(context,actor,question,SourceSpecificQaTrial.Mode.REAL_DOCUMENT_REVOKED);
            assertThat(documentRevoked.modelCalls()).isEqualTo(1);
            assertThat(documentRevoked.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.NO_AUTHORIZED_SPACE));
            assertThat(jdbc.sql("SELECT count(*) FROM qa_answer WHERE request_id=:id")
                    .param("id",documentRevoked.outcome().requestId()).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM acl_entry WHERE scope='DOCUMENT'").query(Long.class).single()).isEqualTo(beforeDocumentAcls);
            var disabled=SourceSpecificQaTrial.run(context,actor,question,SourceSpecificQaTrial.Mode.REAL_USER_DISABLED);
            assertThat(disabled.modelCalls()).isEqualTo(1);
            assertThat(disabled.outcome()).isInstanceOfSatisfying(com.yunjihai.rag.qa.domain.QaOutcome.Refused.class,
                    refused->assertThat(refused.reason()).isEqualTo(com.yunjihai.rag.qa.domain.RefusalReason.NO_AUTHORIZED_SPACE));
            assertThat(jdbc.sql("SELECT count(*) FROM qa_answer WHERE request_id=:id")
                    .param("id",disabled.outcome().requestId()).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT enabled FROM app_user WHERE id=:id").param("id",actor).query(Boolean.class).single()).isTrue();
        }
    }

    private static void assertFixtureCitation(JdbcClient jdbc,com.yunjihai.rag.qa.domain.QaOutcome.Answered answer,String expectedTitle) {
        assertThat(answer.claims()).hasSize(1);
        assertThat(answer.claims().getFirst().evidenceIds()).hasSize(1);
        String title=jdbc.sql("SELECT title FROM qa_evidence_snapshot WHERE request_id=:request AND evidence_id=:evidence")
                .param("request",answer.requestId()).param("evidence",answer.claims().getFirst().evidenceIds().getFirst())
                .query(String.class).single();
        assertThat(title).isEqualTo(expectedTitle);
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_GENERAL_MODEL",matches="true")
    void observesSixOrdinarySyntheticQuestionsWithOfficialModel() throws Exception {
        try(var context=startSyntheticContext(true,false,false)) {
            var jdbc=context.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                    .query(Long.class).single()).isEqualTo(18);
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            try(var input=getClass().getResourceAsStream("/synthetic42-reviewed-contexts.json")) {
                var documents=mapper.readTree(java.util.Objects.requireNonNull(input)).path("文档");
                assertThat(documents.size()).isEqualTo(18);
                var fields=documents.fields();
                while(fields.hasNext()) {
                    var field=fields.next();
                    var fingerprint=jdbc.sql("SELECT qa_document_context_fingerprint(id,current_published_version_id) FROM document WHERE title=:title AND NOT archived")
                            .param("title",field.getKey()).query(String.class).single();
                    assertThat(fingerprint).isEqualTo(field.getValue().asText());
                }
            }
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name").param("name","模拟数据管理员").query(UUID.class).single();
            var questions=new java.util.HashMap<String,String>();
            var expectedTitles=new java.util.HashMap<String,String>();
            try(var input=getClass().getResourceAsStream("/synthetic42-questions.json")) {
                for(var item:mapper.readTree(java.util.Objects.requireNonNull(input)).path("cases"))
                    questions.put(item.path("id").asText(),item.path("question").asText());
            }
            try(var input=getClass().getResourceAsStream("/synthetic42-literal-answer-spans.json")) {
                for(var item:mapper.readTree(java.util.Objects.requireNonNull(input)).path("cases"))
                    expectedTitles.put(item.path("id").asText(),item.path("title").asText());
            }
            var qa=context.getBean(com.yunjihai.rag.qa.GroundedQaService.class);
            int answered=0,expectedSource=0,inputTokens=0,outputTokens=0;
            for(String id:java.util.List.of("Q01","Q02","Q03","Q04","Q07","Q08")) {
                assertThat(questions).containsKey(id); assertThat(expectedTitles).containsKey(id);
                var result=qa.ask(new com.yunjihai.rag.qa.AskQuestionCommand(UUID.randomUUID(),actor,questions.get(id)));
                boolean sourceMatched=false;
                if(result instanceof com.yunjihai.rag.qa.domain.QaOutcome.Answered answer) {
                    answered++;
                    for(var claim:answer.claims())for(var evidence:claim.evidenceIds()) {
                        var title=jdbc.sql("SELECT title FROM qa_evidence_snapshot WHERE request_id=:request AND evidence_id=:evidence")
                                .param("request",answer.requestId()).param("evidence",evidence).query(String.class).single();
                        if(title.equals(expectedTitles.get(id)))sourceMatched=true;
                    }
                    if(sourceMatched)expectedSource++;
                }
                var usage=jdbc.sql("SELECT input_tokens,output_tokens FROM qa_model_call_audit WHERE request_id=:id")
                        .param("id",result.requestId()).query((rs,row)->new int[]{rs.getInt(1),rs.getInt(2)}).optional();
                if(usage.isPresent()){inputTokens+=usage.get()[0];outputTokens+=usage.get()[1];}
                System.out.println("SYNTH_ORDINARY_MODEL id="+id+" status="+result.status()+" expectedSource="+sourceMatched+" modelCalled="+usage.isPresent());
            }
            System.out.println("SYNTH_ORDINARY_SUMMARY total=6 answered="+answered+" expectedSource="+expectedSource
                    +" inputTokens="+inputTokens+" outputTokens="+outputTokens);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="RAG_SYNTH_REAL_MODEL",matches="true")
    void realModelShadowTrialUsesOnlyTheTwoApprovedSyntheticQuestions() throws Exception {
        try(var context=startSyntheticContext(true)) {
            var jdbc=context.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT count(*) FROM document WHERE archived=false AND current_published_version_id IS NOT NULL")
                    .query(Long.class).single()).isEqualTo(18);
            assertThat(jdbc.sql("SELECT count(*) FROM retrieval_projection_event WHERE status <> 'SUCCEEDED'")
                    .query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM knowledge_space WHERE name=:name")
                    .param("name","模拟资料评测空间").query(Long.class).single()).isEqualTo(1);
            try(var hashes=getClass().getResourceAsStream("/synthetic42-source-sha256.json")) {
                var expected=new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        java.util.Objects.requireNonNull(hashes),new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>(){});
                assertThat(expected).hasSize(18).doesNotHaveDuplicates();
                assertThat(jdbc.sql("SELECT sha256 FROM active_blob_reservation ORDER BY sha256").query(String.class).list())
                        .containsExactlyElementsOf(expected);
            }
            assertThat(jdbc.sql("""
                    SELECT md5(string_agg(jsonb_build_array(n.version_id,n.node_id,n.body_text,n.table_cells)::text,
                      chr(10) ORDER BY n.version_id,n.node_id))
                    FROM canonical_node n JOIN document d ON d.current_published_version_id=n.version_id WHERE NOT d.archived
                    """).query(String.class).single()).isEqualTo("ec46d3e867c6b5d864b0a14fd5ec9d3b");
            UUID actor=jdbc.sql("SELECT id FROM app_user WHERE display_name=:name")
                    .param("name","模拟数据管理员").query(UUID.class).single();
            String[][] cases={{"Q05","星河项目的备份保留多久？","备份保留30天"},
                    {"Q06","星河项目多久组织一次恢复演练？","每月组织一次恢复演练"}};
            for(var item:cases) {
                var result=QuestionScopedQaTrial.run(context,actor,item[1],QuestionScopedQaTrial.Mode.GOOD,true);
                assertThat(result.modelCalls()).as(item[0]+" 必须进入真实模型适配器").isEqualTo(1);
                if(result.outcome() instanceof com.yunjihai.rag.qa.domain.QaOutcome.Answered answer) {
                    assertThat(answer.answer()).contains(item[2]);
                    assertFixtureCitation(jdbc,answer);
                }
                System.out.println("SYNTH_REAL_MODEL id="+item[0]+" status="+result.outcome().status()
                        +" exactQuote="+result.exactQuoteSupported());
            }
        }
    }

    private static org.springframework.context.ConfigurableApplicationContext startSyntheticContext() {
        return startSyntheticContext(false);
    }

    private static org.springframework.context.ConfigurableApplicationContext startSyntheticContext(boolean realModel) {
        return startSyntheticContext(realModel,false,false);
    }

    private static org.springframework.context.ConfigurableApplicationContext startSyntheticContext(
            boolean realModel,boolean includeSyntheticPolicy,boolean enableScopedPolicy) {
        return startSyntheticContext(realModel,includeSyntheticPolicy,enableScopedPolicy,false);
    }

    private static org.springframework.context.ConfigurableApplicationContext startSyntheticContext(
            boolean realModel,boolean includeSyntheticPolicy,boolean enableScopedPolicy,boolean builtinPolicy) {
        var config=new HashMap<String,Object>();
        config.put("server.port","0");
        config.put("spring.datasource.url","jdbc:postgresql://127.0.0.1:15432/rag_synthetic");
        config.put("spring.datasource.username","rag_synthetic_runtime");
        config.put("spring.datasource.password",required("RAG_SYNTH_DB_RUNTIME_PASSWORD"));
        config.put("spring.flyway.enabled","false");
        config.put("rag.bootstrap-admin.enabled","false");
        config.put("rag.workers.enabled","false");
        if(realModel && !"true".equals(System.getenv("RAG_SYNTH_REAL_MODEL")))
            throw new IllegalStateException("SYNTHETIC_REAL_MODEL_NOT_ENABLED");
        config.put("rag.ai.enabled",Boolean.toString(realModel));
        config.put("rag.qa.question-scoped-conflicts.enabled",Boolean.toString(enableScopedPolicy));
        config.put("rag.qa.question-scoped-conflicts.builtin-backup-policy",Boolean.toString(builtinPolicy));
        if(realModel) {
            config.put("rag.ai.provider","deepseek");
            config.put("rag.ai.config-version","synthetic-shadow-v1");
            config.put("rag.ai.endpoint","https://api.deepseek.com");
            config.put("rag.ai.approved-hosts","api.deepseek.com");
            config.put("rag.ai.api-key",required("RAG_AI_API_KEY"));
            config.put("rag.ai.chat-model","deepseek-flash");
            config.put("rag.ai.connect-timeout","PT3S");
            config.put("rag.ai.response-timeout","PT20S");
            config.put("rag.ai.total-timeout","PT25S");
            config.put("rag.ai.max-input-tokens","8000");
            config.put("rag.ai.max-output-tokens","1000");
            config.put("rag.ai.max-concurrency","1");
            config.put("rag.ai.daily-deployment-token-limit","18000");
        }
        config.put("rag.minio.endpoint","true".equals(System.getenv("RAG_SYNTH_ALT_MINIO_PORT"))
                ? "http://127.0.0.1:29000" : "http://127.0.0.1:19000");
        config.put("rag.minio.access-key","rag_synthetic_app");
        config.put("rag.minio.secret-key",required("RAG_MINIO_SECRET_KEY"));
        config.put("rag.minio.bucket","rag-documents");
        config.put("rag.opensearch.enabled","true");
        config.put("rag.opensearch.endpoint","http://127.0.0.1:19200");
        config.put("rag.retrieval.vector.enabled","false");
        config.put("rag.qa.hmac-key","synthetic-complete-screening-test-hmac-key");
        config.put("rag.retrieval.local-rerank.enabled",Boolean.toString("true".equals(System.getenv("RAG_SYNTH_REPLAY_LOCAL_RERANK"))));
        config.put("logging.level.root","ERROR");
        var builder=new SpringApplicationBuilder(RagApplication.class);
        if(includeSyntheticPolicy)builder.sources(SyntheticScopedPolicyConfiguration.class);
        boolean reviewed="true".equals(System.getenv("RAG_SYNTH_REVIEWED_TRIAL"));
        if(realModel && reviewed)builder.sources(SyntheticModelDiagnosticConfiguration.class);
        if((builtinPolicy || reviewed) && !realModel)builder.sources(SyntheticChatOnlyConfiguration.class);
        java.nio.file.Path manifest=null;
        try {
            if(reviewed) {
                var bootstrap=JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:15432/rag_synthetic","rag_synthetic_runtime",required("RAG_SYNTH_DB_RUNTIME_PASSWORD")));
                var digests=new com.yunjihai.rag.infrastructure.crypto.HmacQuestionDigest(
                        "synthetic-complete-screening-test-hmac-key".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                manifest=java.nio.file.Files.createTempFile("synthetic-reviewed-qa-", ".json");
                java.nio.file.Files.write(manifest,ReviewedSyntheticContextConfiguration.signedManifest(bootstrap,digests));
                config.put("rag.qa.question-scoped-conflicts.reviewed-manifest",manifest.toAbsolutePath().toString());
            }
            return builder.profiles("local")
                    .initializers(app->app.getEnvironment().getPropertySources()
                            .addFirst(new MapPropertySource("synthetic-complete-screening",config))).run();
        } catch(Exception failure) {
            throw new IllegalStateException("SYNTHETIC_CONTEXT_SETUP_FAILED",failure);
        } finally {
            if(manifest!=null)try {java.nio.file.Files.deleteIfExists(manifest);} catch(java.io.IOException ignored) { }
        }
    }

    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank())throw new IllegalStateException("synthetic test configuration missing: "+name);
        return value;
    }
}
