package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.infrastructure.postgres.JdbcTableEvidenceSource;
import com.yunjihai.rag.qa.*;
import com.yunjihai.rag.qa.conflict.*;
import com.yunjihai.rag.qa.domain.*;
import com.yunjihai.rag.qa.model.*;
import com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.retrieval.domain.EvidencePack;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Synthetic-only, extracts a whole table and never becomes a production bean. */
final class SourceSpecificQaTrial {
    enum Mode { GOOD, WRONG_RECORD, PARTIAL, EXTRA_CONFLICT, TABLE_FAILURE, FINAL_REVOKED, REAL_USER_DISABLED, REAL_SPACE_REVOKED, REAL_DOCUMENT_REVOKED }
    record Result(QaOutcome outcome,int modelCalls,boolean exactQuoteSupported) { }
    static Result run(ConfigurableApplicationContext context,UUID actor,String question,Mode mode) {
        var calls=new AtomicInteger();
        var exactQuote=new java.util.concurrent.atomic.AtomicBoolean();
        var disabledUser=new java.util.concurrent.atomic.AtomicBoolean();
        var revokedAcl=new AtomicReference<UUID>();
        var insertedDocumentAcl=new AtomicReference<UUID>();
        var chosen=new AtomicReference<Evidence>();
        TableEvidenceSource tables=mode==Mode.TABLE_FAILURE
                ? ignored->{throw new IllegalStateException("SYNTHETIC_TABLE_FAILURE");}
                : new JdbcTableEvidenceSource(context.getBean(JdbcClient.class));
        var detector=new DeterministicEvidenceConflictDetector(tables);
        EvidenceConflictDetector scoped=items->{
            if(detector.conflictKind(items)==EvidenceConflictDetector.ConflictKind.NONE)return false;
            var byId=items.stream().collect(Collectors.toMap(Evidence::evidenceId,item->item));
            var pairs=detector.findConflictingPairs(items);
            if(pairs.isEmpty())return true;
            Evidence target=null;
            for(var pair:pairs) {
                var selected=SourceSpecificConflictDiagnostic.target(question,pair,byId);
                if(selected.isEmpty())return true;
                var first=byId.get(pair.firstEvidenceId());
                var second=byId.get(pair.secondEvidenceId());
                String project=QuestionScopedConflictShadow.singleBudgetProject(tables.load(first));
                if(project==null || !project.equals(QuestionScopedConflictShadow.singleBudgetProject(tables.load(second))))return true;
                var candidate=byId.get(selected.orElseThrow());
                if(target!=null && !target.evidenceId().equals(candidate.evidenceId()))return true;
                target=candidate;
            }
            chosen.set(target);
            return false;
        };
        var actual=context.getBean(RetrievalService.class);
        RetrievalService retrieval=query->{
            var pack=actual.retrieve(query);
            if(mode!=Mode.EXTRA_CONFLICT)return pack;
            var item=pack.conflictScreeningEvidence().stream().filter(e->e.title().equals("05_星河项目预算记录甲"))
                    .findFirst().orElseThrow();
            var extra=new Evidence(item.spaceId(),UUID.randomUUID(),item.documentId(),item.versionId(),item.nodeId(),
                    item.title(),"存在未知变化",item.score(),item.locator());
            var screening=new ArrayList<>(pack.conflictScreeningEvidence()); screening.add(extra);
            return new EvidencePack(pack.requestId(),pack.evidence(),pack.degraded(),pack.degradationReasons(),
                    pack.stats(),pack.completedAt(),screening);
        };
        ChatCompletionPort fake=new ChatCompletionPort() {
            @Override public ModelCallDescriptor descriptor(){return new ModelCallDescriptor("synthetic","local-test","fixture-only","fixture-v1");}
            @Override public ModelGeneration generate(GroundedAnswerPrompt prompt){
                calls.incrementAndGet();
                var selected=prompt.evidence().evidence().stream().filter(item->mode==Mode.WRONG_RECORD
                        ? item.locator() instanceof com.yunjihai.rag.retrieval.domain.TableLocator
                            && item.title().contains("预算记录") && !item.title().equals(chosen.get().title())
                        : item.spaceId().equals(chosen.get().spaceId()) && item.documentId().equals(chosen.get().documentId())
                            && item.versionId().equals(chosen.get().versionId()) && item.nodeId().equals(chosen.get().nodeId()))
                        .findFirst().orElseThrow();
                String text=mode==Mode.PARTIAL ? "未注明" : selected.excerpt();
                var support=new EvidenceSupport(selected.evidenceId(),selected.excerpt());
                var answer=new CandidateAnswer(CandidateAnswer.Decision.ANSWER,text,
                        List.of(new GroundedClaim(UUID.randomUUID(),text,List.of(support))));
                if(mode==Mode.REAL_USER_DISABLED) {
                    int changed=context.getBean(JdbcClient.class).sql("UPDATE app_user SET enabled=false WHERE id=:id AND enabled=true")
                            .param("id",actor).update();
                    if(changed!=1)throw new IllegalStateException("SYNTHETIC_USER_STATE_UNEXPECTED");
                    disabledUser.set(true);
                }
                if(mode==Mode.REAL_SPACE_REVOKED) {
                    var ids=context.getBean(JdbcClient.class).sql("""
                            UPDATE acl_entry SET revoked_at=now(),updated_at=now()
                            WHERE scope='SPACE' AND principal_type='USER' AND principal_id=:actor
                              AND space_id=:space AND revoked_at IS NULL RETURNING id
                            """).param("actor",actor).param("space",chosen.get().spaceId()).query(UUID.class).list();
                    if(ids.size()!=1)throw new IllegalStateException("SYNTHETIC_ACL_STATE_UNEXPECTED");
                    revokedAcl.set(ids.getFirst());
                }
                if(mode==Mode.REAL_DOCUMENT_REVOKED) {
                    var id=UUID.randomUUID();
                    int changed=context.getBean(JdbcClient.class).sql("""
                            INSERT INTO acl_entry(id,space_id,document_id,scope,principal_type,principal_id,revoked_at)
                            SELECT :id,:space,:document,'DOCUMENT','USER',:actor,now()
                            WHERE NOT EXISTS (SELECT 1 FROM acl_entry WHERE scope='DOCUMENT' AND document_id=:document)
                            """).param("id",id).param("space",chosen.get().spaceId())
                            .param("document",chosen.get().documentId()).param("actor",actor).update();
                    if(changed!=1)throw new IllegalStateException("SYNTHETIC_DOCUMENT_ACL_STATE_UNEXPECTED");
                    insertedDocumentAcl.set(id);
                }
                return new ModelGeneration(answer,new ModelUsage(0,0,Duration.ZERO),"synthetic",0);
            }
        };
        CitationSupportVerifier verifier=(answer,bundle)->{
            boolean exact=new ExactQuoteCitationSupportVerifier().isSupported(answer,bundle);
            exactQuote.set(exact);
            return exact && SourceSpecificAnswerShadow.supports(question,answer,bundle,chosen.get());
        };
        QaEvidenceAuthorizationPort auth=mode==Mode.FINAL_REVOKED ? (requestId,actorId,at)->false
                : context.getBean(QaEvidenceAuthorizationPort.class);
        var qa=new GroundedQaService(context.getBean(AuthorizedSpaceScopePort.class),retrieval,
                context.getBean(QaPersistencePort.class),context.getBean(ModelCallAuditPort.class),
                context.getBean(QuestionDigestPort.class),context.getBean(SensitiveContentSanitizer.class),
                fake,scoped,verifier,context.getBean(QaPolicy.class),auth,context.getBean(Clock.class));
        try {
            return new Result(qa.ask(new AskQuestionCommand(UUID.randomUUID(),actor,question)),calls.get(),exactQuote.get());
        } finally {
            if(disabledUser.get()) {
                int changed=context.getBean(JdbcClient.class).sql("UPDATE app_user SET enabled=true WHERE id=:id AND enabled=false")
                        .param("id",actor).update();
                if(changed!=1)throw new IllegalStateException("SYNTHETIC_USER_RESTORE_FAILED");
            }
            if(revokedAcl.get()!=null) {
                int changed=context.getBean(JdbcClient.class).sql("UPDATE acl_entry SET revoked_at=NULL,updated_at=now() WHERE id=:id AND revoked_at IS NOT NULL")
                        .param("id",revokedAcl.get()).update();
                if(changed!=1)throw new IllegalStateException("SYNTHETIC_ACL_RESTORE_FAILED");
            }
            if(insertedDocumentAcl.get()!=null) {
                int changed=context.getBean(JdbcClient.class).sql("DELETE FROM acl_entry WHERE id=:id AND scope='DOCUMENT' AND revoked_at IS NOT NULL")
                        .param("id",insertedDocumentAcl.get()).update();
                if(changed!=1)throw new IllegalStateException("SYNTHETIC_DOCUMENT_ACL_CLEANUP_FAILED");
            }
        }
    }
}
