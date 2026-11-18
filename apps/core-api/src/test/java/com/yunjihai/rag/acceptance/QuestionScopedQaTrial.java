package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.infrastructure.postgres.JdbcTableEvidenceSource;
import com.yunjihai.rag.qa.*;
import com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector;
import com.yunjihai.rag.qa.conflict.TableEvidenceSource;
import com.yunjihai.rag.qa.conflict.QuestionScopedConflictPolicy;
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
import java.util.stream.Collectors;

/** Synthetic-only fixed-fixture experiment. Never register this as a production bean. */
final class QuestionScopedQaTrial {
    enum Mode { GOOD, UNRELATED_ANSWER, EXTRA_CONFLICT, TABLE_FAILURE, FINAL_REVOKED }
    record Result(QaOutcome outcome,int modelCalls,boolean exactQuoteSupported) { }

    static Result run(ConfigurableApplicationContext context,UUID actor,String question,Mode mode) {
        return run(context,actor,question,mode,false);
    }

    static Result run(ConfigurableApplicationContext context,UUID actor,String question,Mode mode,boolean realModel) {
        var counter=new AtomicInteger();
        var quoteAccepted=new java.util.concurrent.atomic.AtomicBoolean();
        TableEvidenceSource source=mode==Mode.TABLE_FAILURE
                ? ignored -> { throw new IllegalStateException("SYNTHETIC_TABLE_FAILURE"); }
                : new JdbcTableEvidenceSource(context.getBean(JdbcClient.class));
        var detector=new DeterministicEvidenceConflictDetector(source);
        QuestionScopedConflictPolicy scoped=new QuestionScopedConflictPolicy() {
            @Override public String version() { return "synthetic-v1"; }
            @Override public Scope classify(QaQuestion asked,DeterministicEvidenceConflictDetector.ConflictPair pair,
                                            List<Evidence> items) {
                var byId=items.stream().collect(Collectors.toMap(Evidence::evidenceId,e->e));
                return QuestionScopedConflictShadow.assess(asked.value(),pair,byId,source)
                        ==QuestionScopedConflictShadow.Scope.EXPLICITLY_UNRELATED ? Scope.UNRELATED : Scope.UNKNOWN;
            }
            @Override public AnswerRelevance verifyAnswer(QaQuestion asked,CandidateAnswer answer,EvidenceBundle bundle) {
                return QuestionScopedAnswerShadow.supports(asked.value(),answer,bundle)
                        ? AnswerRelevance.RELEVANT : AnswerRelevance.UNKNOWN;
            }
        };
        var actualRetrieval=context.getBean(RetrievalService.class);
        RetrievalService retrieval=query -> {
            var pack=actualRetrieval.retrieve(query);
            if(mode!=Mode.EXTRA_CONFLICT)return pack;
            var existing=pack.conflictScreeningEvidence().stream()
                    .filter(e->e.title().equals("03_星河项目数据备份制度") && e.excerpt().contains("备份保留30天"))
                    .findFirst().orElseThrow();
            var extra=new Evidence(existing.spaceId(),UUID.randomUUID(),existing.documentId(),existing.versionId(),
                    existing.nodeId(),existing.title(),"备份保留90天",existing.score(),existing.locator());
            var all=new ArrayList<>(pack.conflictScreeningEvidence()); all.add(extra);
            return new EvidencePack(pack.requestId(),pack.evidence(),pack.degraded(),pack.degradationReasons(),
                    pack.stats(),pack.completedAt(),all);
        };
        String expected=question.contains("恢复演练") ? "每月组织一次恢复演练" : "备份保留30天";
        ChatCompletionPort fake=new ChatCompletionPort() {
            @Override public ModelCallDescriptor descriptor() {
                return new ModelCallDescriptor("synthetic","local-test","fixture-only","fixture-v1");
            }
            @Override public ModelGeneration generate(GroundedAnswerPrompt prompt) {
                counter.incrementAndGet();
                String text=mode==Mode.UNRELATED_ANSWER ? "研发预算" : expected;
                var item=prompt.evidence().evidence().stream()
                        .filter(e->mode==Mode.UNRELATED_ANSWER ? e.title().equals("05_星河项目预算记录甲")
                                && e.excerpt().contains(text) : e.title().equals("03_星河项目数据备份制度")
                                && e.excerpt().contains(text))
                        .findFirst().orElseThrow();
                var support=new EvidenceSupport(item.evidenceId(),item.excerpt());
                var claim=new GroundedClaim(UUID.randomUUID(),text,List.of(support));
                var answer=new CandidateAnswer(CandidateAnswer.Decision.ANSWER,text,List.of(claim));
                return new ModelGeneration(answer,new ModelUsage(0,0,Duration.ZERO),"synthetic",0);
            }
        };
        ChatCompletionPort chat=realModel ? new ChatCompletionPort() {
            private final ChatCompletionPort delegate=context.getBean(ChatCompletionPort.class);
            @Override public ModelCallDescriptor descriptor(){return delegate.descriptor();}
            @Override public ModelGeneration generate(GroundedAnswerPrompt prompt){
                var jdbc=context.getBean(JdbcClient.class);
                for(var item:prompt.evidence().evidence()) {
                    var row=jdbc.sql("""
                            SELECT n.body_text,d.title FROM canonical_node n JOIN document d
                              ON d.current_published_version_id=n.version_id
                            WHERE d.id=:document AND d.space_id=:space AND NOT d.archived
                              AND n.version_id=:version AND n.node_id=:node
                            """).param("document",item.documentId()).param("space",item.spaceId())
                            .param("version",item.versionId()).param("node",item.nodeId())
                            .query((rs,index)->new String[]{rs.getString(1),rs.getString(2)}).single();
                    if(!row[0].startsWith(item.excerpt()) || !row[1].equals(item.title()))
                        throw new IllegalStateException("SYNTHETIC_OUTBOUND_EVIDENCE_MISMATCH");
                }
                counter.incrementAndGet();
                var generated=delegate.generate(prompt);
                var candidate=generated.answer();
                String expectedText=question.contains("恢复演练") ? "每月组织一次恢复演练" : "备份保留30天";
                var sources=prompt.evidence().evidence().stream().collect(Collectors.toMap(QaEvidence::evidenceId,item->item));
                long supportCount=candidate.claims().stream().mapToLong(claim->claim.supports().size()).sum();
                boolean correctSource=supportCount>0 && candidate.claims().stream().flatMap(claim->claim.supports().stream())
                        .allMatch(support->{var item=sources.get(support.evidenceId());
                            return item!=null && item.title().equals("03_星河项目数据备份制度");});
                boolean fullQuote=supportCount>0 && candidate.claims().stream().flatMap(claim->claim.supports().stream())
                        .allMatch(support->{var item=sources.get(support.evidenceId());
                            return item!=null && item.excerpt().equals(support.quote());});
                boolean fullSentence=candidate.claims().stream().flatMap(claim->claim.supports().stream())
                        .anyMatch(support->{var item=sources.get(support.evidenceId());
                            return item!=null && java.util.Arrays.stream(item.excerpt().split("。"))
                                    .map(String::strip).anyMatch(sentence->!sentence.isBlank()
                                            && sentence.equals(candidate.answer().strip().replaceAll("。$","")));});
                System.out.println("SYNTH_REAL_MODEL_SHAPE decision="+candidate.decision()+" claims="+candidate.claims().size()
                        +" expectedAnswer="+expectedText.equals(candidate.answer())+" correctSource="+correctSource
                        +" containsExpected="+candidate.answer().contains(expectedText)+" fullQuote="+fullQuote
                        +" fullSentence="+fullSentence);
                return generated;
            }
        } : fake;
        CitationSupportVerifier verifier=(answer,bundle) -> {
            boolean exact=new ExactQuoteCitationSupportVerifier().isSupported(answer,bundle);
            quoteAccepted.set(exact);
            return exact;
        };
        QaEvidenceAuthorizationPort finalAuthorization=mode==Mode.FINAL_REVOKED
                ? (requestId,actorId,at)->false : context.getBean(QaEvidenceAuthorizationPort.class);
        var qa=new GroundedQaService(context.getBean(AuthorizedSpaceScopePort.class),retrieval,
                context.getBean(QaPersistencePort.class),context.getBean(ModelCallAuditPort.class),
                context.getBean(QuestionDigestPort.class),context.getBean(SensitiveContentSanitizer.class),
                chat,detector,verifier,context.getBean(QaPolicy.class),finalAuthorization,
                context.getBean(Clock.class),QaMetrics.noop(),new com.yunjihai.rag.qa.evaluation.EvaluationTraceRegistry(),scoped);
        var outcome=qa.ask(new AskQuestionCommand(UUID.randomUUID(),actor,question));
        return new Result(outcome,counter.get(),quoteAccepted.get());
    }
}
