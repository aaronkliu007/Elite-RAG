package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.generation.SectionVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.verification.SolutionRiskReport;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class SolutionSectionAdoptionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");

    @Test void adoptsInsideOneTransactionAndReplaysOnlyAfterAuthorization() {
        var actor = UUID.randomUUID();
        var space = UUID.randomUUID();
        var solutionId = UUID.randomUUID();
        var sectionId = UUID.randomUUID();
        var candidateId = UUID.randomUUID();
        var initial = new RequirementVersion(UUID.randomUUID(), 1,
                List.of(new RequirementItem(UUID.randomUUID(), RequirementCategory.UNKNOWN,
                        "待核实目标", List.of())), actor, NOW, "初始录入");
        var outline = new OutlineVersion(UUID.randomUUID(), 1,
                List.of(new OutlineNode(sectionId, "总体方案", 1, 1, true,
                        "描述方案", "方案检索", Set.of())), actor, NOW);
        var confirmed = Solution.create(solutionId, space, actor, initial, NOW)
                .confirmRequirements(actor, NOW, 0).reviseOutline(outline, 1)
                .confirmOutline(actor, NOW, 2);
        var candidate = new SectionVersion(candidateId, solutionId, sectionId,
                SectionVersionKind.MODEL_DRAFT, "候选方案", List.of(), Set.of(),
                initial.id(), outline.id(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of("model"), NOW, new SolutionRiskReport(List.of()));
        SectionVersionRepository sections = new SectionVersionRepository() {
            public Optional<SectionVersion> findById(UUID id) {
                return id.equals(candidateId) ? Optional.of(candidate) : Optional.empty();
            }
            public SectionVersion insertIfAbsent(SectionVersion ignored) { throw new UnsupportedOperationException(); }
            public Optional<SectionVersion> findByAttemptId(UUID id) { return Optional.empty(); }
        };
        var current = new AtomicReference<>(confirmed);
        SolutionRepository solutions = new SolutionRepository() {
            public Optional<Solution> findById(UUID id) { return id.equals(solutionId)
                    ? Optional.of(current.get()) : Optional.empty(); }
            public void insert(Solution ignored) { throw new UnsupportedOperationException(); }
            public void replace(Solution value, long expected) { current.set(value); }
        };
        var txActive = new AtomicBoolean();
        var transactions = new TransactionTemplate(new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                txActive.set(true);
                return new SimpleTransactionStatus();
            }
            public void commit(TransactionStatus status) { txActive.set(false); }
            public void rollback(TransactionStatus status) { txActive.set(false); }
        });
        var calls = new AtomicInteger();
        var auditEvents = new ArrayList<AuditCommand>();
        var authorized = new AtomicBoolean(true);
        var stored = new HashMap<SolutionCommandIdempotencyStore.Key, SolutionCommandResult>();
        SolutionCommandIdempotencyStore idempotency = new SolutionCommandIdempotencyStore() {
            public Optional<SolutionCommandResult> claim(Key key, String digest) {
                return Optional.ofNullable(stored.get(key));
            }
            public void complete(Key key, SolutionCommandResult result) { stored.put(key, result); }
            public void release(Key key) { stored.remove(key); }
        };
        var service = new SolutionSectionAdoptionService(solutions, sections,
                (user, targetSpace, target, action, at) -> {
                    if (!authorized.get()) throw new com.yunjihai.rag.access.AccessDeniedAsNotFoundException();
                }, idempotency, transactions,
                (user, target, candidateVersionId, revision, at) -> {
                    assertThat(txActive.get()).isTrue();
                    calls.incrementAndGet();
                    var adopted = current.get().adoptSection(sectionId, candidateId, revision);
                    current.set(adopted);
                    return adopted;
                }, command -> { auditEvents.add(command); return null; },
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.adopt(actor, solutionId, sectionId, candidateId,
                confirmed.revision(), "adopt-key");
        assertThat(result.revision()).isEqualTo(4);
        assertThat(service.adopt(actor, solutionId, sectionId, candidateId,
                confirmed.revision(), "adopt-key")).isEqualTo(result);
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> service.adopt(actor, solutionId, UUID.randomUUID(), candidateId,
                confirmed.revision(), "wrong-section"))
                .isInstanceOf(com.yunjihai.rag.access.AccessDeniedAsNotFoundException.class);
        assertThat(auditEvents).singleElement().satisfies(event ->
                assertThat(event.outcome()).isEqualTo(AuditOutcome.DENIED));
        auditEvents.clear();
        authorized.set(false);
        assertThatThrownBy(() -> service.adopt(actor, solutionId, sectionId, candidateId,
                confirmed.revision(), "adopt-key"))
                .isInstanceOf(com.yunjihai.rag.access.AccessDeniedAsNotFoundException.class);
        assertThat(auditEvents).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(AuditOutcome.DENIED);
            assertThat(event.metadata()).containsEntry("errorCode", "SECTION_ADOPTION_FAILED");
        });
    }
}
