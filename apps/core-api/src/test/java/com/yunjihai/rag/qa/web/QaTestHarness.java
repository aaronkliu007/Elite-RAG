package com.yunjihai.rag.qa.web;

import com.yunjihai.rag.api.idempotency.*;
import com.yunjihai.rag.qa.*;
import com.yunjihai.rag.qa.domain.*;
import com.yunjihai.rag.qa.model.*;
import com.yunjihai.rag.qa.sanitization.*;
import com.yunjihai.rag.retrieval.RetrievalService;
import com.yunjihai.rag.retrieval.domain.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.CountDownLatch;

final class QaTestHarness {
    static final Instant NOW=Instant.parse("2026-10-10T08:00:00Z");
    static final UUID ACTOR=id(1);
    final MemoryQaPersistence persistence=new MemoryQaPersistence();
    final AtomicInteger modelCalls=new AtomicInteger();
    final AtomicInteger rateLimitAcquisitions=new AtomicInteger();
    final AtomicBoolean rejectRateLimit=new AtomicBoolean();
    final AtomicReference<UUID> lastActor=new AtomicReference<>();
    final CountDownLatch modelEntered=new CountDownLatch(1);
    final CountDownLatch releaseModel=new CountDownLatch(1);
    final boolean blockModel;
    final QaController controller;

    QaTestHarness(Mode mode) { this(mode,false); }

    QaTestHarness(Mode mode,boolean blockModel) {
        this.blockModel=blockModel;
        AuthorizedSpaceScopePort spaces=(actor,at)->{
            lastActor.set(actor);
            if(mode==Mode.REFUSED)return Set.of();
            if(mode==Mode.FAILED)return java.util.stream.IntStream.range(0,101)
                    .mapToObj(index->id(1000+index))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return Set.of(id(10));
        };
        List<Evidence> evidence=java.util.stream.IntStream.range(0,8)
                .mapToObj(index->new Evidence(id(10),id(100+index),id(200+index),id(300+index),
                        "node-"+index,"制度",index==0?"制度已经生效。":"证据正文 "+index,0.9,
                        new MarkdownLocator(List.of("第一章"),"node-"+index)))
                .toList();
        RetrievalService retrieval=query->new EvidencePack(query.requestId(),evidence,false,
                Set.of(),new RetrievalStats(8,0,8,8,8),NOW);
        SensitiveContentSanitizer sanitizer=new SensitiveContentSanitizer() {
            public SanitizationResult sanitize(EvidenceBundle value) {
                return new SanitizationResult(value,Map.of(),false);
            }
            public SanitizedText sanitizeText(String value) {
                return new SanitizedText(value,Map.of(),false);
            }
        };
        ChatCompletionPort chat=new ChatCompletionPort() {
            @Override public ModelCallDescriptor descriptor() {
                return new ModelCallDescriptor("test-provider","chat-completions","test-model","test-config-v1");
            }
            @Override public ModelGeneration generate(GroundedAnswerPrompt prompt) {
                modelCalls.incrementAndGet();
                modelEntered.countDown();
                if(blockModel) {
                    try { releaseModel.await(); }
                    catch(InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test interrupted",interrupted);
                    }
                }
                var claim=new GroundedClaim(id(800),"制度已经生效。",
                        List.of(new EvidenceSupport(id(100),"制度已经生效。")));
                return new ModelGeneration(new CandidateAnswer(CandidateAnswer.Decision.ANSWER,
                        "制度已经生效。",List.of(claim)),new ModelUsage(10,5,Duration.ofMillis(2)),"provider",0);
            }
        };
        var service=new GroundedQaService(spaces,retrieval,persistence,ignored->{},
                ignored->"a".repeat(64),sanitizer,chat,new ExactQuoteCitationSupportVerifier(),
                QaPolicy.defaults(),(requestId,actorId,at)->true,
                Clock.fixed(NOW,ZoneOffset.UTC));
        var json=new ObjectMapper();
        var idempotency=new IdempotencyService(new MemoryIdempotencyStore(),json,
                Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofHours(24));
        QaActorRateLimiter rateLimiter=actorId->{
            rateLimitAcquisitions.incrementAndGet();
            if(rejectRateLimit.get())throw new QaRateLimitExceededException(30);
        };
        controller=new QaController(service,idempotency,rateLimiter,json,Clock.fixed(NOW,ZoneOffset.UTC));
    }

    enum Mode { ANSWERED,REFUSED,FAILED }
    static UsernamePasswordAuthenticationToken auth(UUID id) {
        return new UsernamePasswordAuthenticationToken(id.toString(),"n/a",List.of());
    }
    static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }

    static final class MemoryQaPersistence implements QaPersistencePort {
        private final Map<UUID,Entry> values=new HashMap<>();
        public synchronized RequestState create(UUID requestId,UUID actorId,String digest,Instant at) {
            values.putIfAbsent(requestId,new Entry(new RequestState(
                    requestId,actorId,QaStatus.RECEIVED,0,false),null,null));
            return values.get(requestId).state;
        }
        public synchronized Optional<RequestState> transition(
                UUID id,long revision,QaStatus next,Instant at) {
            Entry old=values.get(id);
            if(old==null||old.state.revision()!=revision)return Optional.empty();
            old.state.status().advanceTo(next);
            var state=new RequestState(id,old.state.actorId(),next,revision+1,old.state.degraded());
            values.put(id,new Entry(state,old.outcome,old.completedAt));
            return Optional.of(state);
        }
        public synchronized Optional<RequestState> saveEvidence(
                UUID id,long revision,EvidenceBundle evidence,boolean degraded,Instant at) {
            Entry old=values.get(id);
            if(old==null||old.state.revision()!=revision)return Optional.empty();
            var state=new RequestState(id,old.state.actorId(),QaStatus.SANITIZING,revision+1,degraded);
            values.put(id,new Entry(state,null,null));
            return Optional.of(state);
        }
        public synchronized Optional<RequestState> complete(
                UUID id,long revision,QaOutcome outcome,Instant at) {
            Entry old=values.get(id);
            if(old==null||old.state.revision()!=revision)return Optional.empty();
            var state=new RequestState(id,old.state.actorId(),outcome.status(),revision+1,old.state.degraded());
            values.put(id,new Entry(state,outcome,at));
            return Optional.of(state);
        }
        public synchronized Optional<QaOutcome> find(UUID id,UUID actor) {
            Entry value=values.get(id);
            return value==null||!value.state.actorId().equals(actor)
                    ?Optional.empty():Optional.ofNullable(value.outcome);
        }
        public synchronized Optional<Instant> completedAt(UUID id,UUID actor) {
            Entry value=values.get(id);
            return value==null||!value.state.actorId().equals(actor)
                    ?Optional.empty():Optional.ofNullable(value.completedAt);
        }
        synchronized int size(){return values.size();}
        private record Entry(RequestState state,QaOutcome outcome,Instant completedAt){}
    }

    static final class MemoryIdempotencyStore implements IdempotencyStore {
        private final Map<IdempotencyScope,Value> values=new HashMap<>();
        public synchronized Claim claim(IdempotencyScope scope,String hash,Instant now,Instant expires) {
            Value value=values.get(scope);
            if(value==null){values.put(scope,new Value(hash,null));return new Claim.Acquired();}
            if(!value.hash.equals(hash))return new Claim.HashMismatch();
            return value.result==null?new Claim.InProgress():new Claim.Completed(value.result);
        }
        public synchronized void complete(IdempotencyScope scope,String hash,StoredHttpResult result,Instant at) {
            values.put(scope,new Value(hash,result));
        }
        public synchronized void release(IdempotencyScope scope,String hash){values.remove(scope);}
        private record Value(String hash,StoredHttpResult result){}
    }
}
