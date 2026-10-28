package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.generation.*;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.verification.*;
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

import static org.assertj.core.api.Assertions.*;

class SolutionSectionEditingServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-29T04:00:00Z");
    private final UUID actor = UUID.randomUUID(), space = UUID.randomUUID(), section = UUID.randomUUID();
    private final UUID evidence = UUID.randomUUID(), document = UUID.randomUUID(), version = UUID.randomUUID();
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private final AtomicBoolean evidenceAllowed = new AtomicBoolean(true);
    private final Map<UUID, SectionVersion> versions = new HashMap<>();
    private final List<AuditCommand> audit = new ArrayList<>();
    private final RequirementVersion requirement = new RequirementVersion(UUID.randomUUID(), 1,
            List.of(new RequirementItem(UUID.randomUUID(), RequirementCategory.UNKNOWN,
                    "待确认需求", List.of())), actor, NOW, "初始录入");
    private final OutlineVersion outline = new OutlineVersion(UUID.randomUUID(), 1,
            List.of(new OutlineNode(section, "总体设计", 1, 1, true,
                    "设计方案", "方案资料", Set.of())), actor, NOW);
    private final Solution initial = Solution.create(UUID.randomUUID(), space, actor, requirement, NOW)
            .confirmRequirements(actor, NOW, 0).reviseOutline(outline, 1)
            .confirmOutline(actor, NOW, 2).adoptSection(section, UUID.randomUUID(), 3);
    private final UUID baseline = initial.adoptedSectionVersionIds().get(section);

    private SolutionSectionEditingService service() {
        var claimId = UUID.randomUUID();
        versions.put(baseline, new SectionVersion(baseline, initial.id(), section,
                SectionVersionKind.ADOPTED, "旧版事实", List.of(new Claim(claimId, "旧版事实", true,
                        Set.of(evidence), Set.of())), Set.of(evidence), requirement.id(), outline.id(),
                Optional.empty(), Optional.empty(), Optional.of(actor), Optional.empty(), NOW,
                new SolutionRiskReport(List.of(new ClaimVerification(claimId,
                        CitationVerdict.SUPPORTED, "SEMANTIC_SUPPORTED")))));
        SolutionRepository solutions = new SolutionRepository() {
            public Optional<Solution> findById(UUID id) { return id.equals(initial.id())
                    ? Optional.of(initial) : Optional.empty(); }
            public void insert(Solution ignored) { throw new UnsupportedOperationException(); }
            public void replace(Solution ignored, long expected) { throw new UnsupportedOperationException(); }
        };
        SectionVersionRepository sections = new SectionVersionRepository() {
            public Optional<SectionVersion> findById(UUID id) { return Optional.ofNullable(versions.get(id)); }
            public SectionVersion insertIfAbsent(SectionVersion value) {
                versions.putIfAbsent(value.id(), value);
                return versions.get(value.id());
            }
            public Optional<SectionVersion> findByAttemptId(UUID id) { return Optional.empty(); }
        };
        SectionEditVersionQueryPort history = new SectionEditVersionQueryPort() {
            public Optional<SectionVersion> latestEdit(UUID solutionId, UUID sectionId, UUID baselineId) {
                return versions.values().stream().filter(value -> value.kind() == SectionVersionKind.HUMAN_EDIT)
                        .filter(value -> value.solutionId().equals(solutionId)
                                && value.sectionId().equals(sectionId)
                                && value.baselineSectionVersionId().equals(Optional.of(baselineId)))
                        .filter(value -> versions.values().stream().noneMatch(child ->
                                child.kind() == SectionVersionKind.HUMAN_EDIT
                                && child.parentVersionId().equals(Optional.of(value.id()))))
                        .findFirst();
            }
            public List<UUID> listIds(UUID solutionId, UUID sectionId, int limit) { return List.of(); }
        };
        GenerationEvidenceStore evidenceStore = new GenerationEvidenceStore() {
            public void saveSnapshot(UUID id, List<GenerationEvidenceSource> sources) { }
            public List<GenerationEvidenceSource> findByAttemptId(UUID id) { return List.of(); }
            public List<GenerationEvidenceSource> findBySectionVersionId(UUID id) {
                return List.of(new GenerationEvidenceSource(1, evidence, space, document, version,
                        "node-1", "a".repeat(64)));
            }
        };
        var transactions = new TransactionTemplate(new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            public void commit(TransactionStatus status) { }
            public void rollback(TransactionStatus status) { }
        });
        return new SolutionSectionEditingService(solutions, sections, history, evidenceStore,
                (user, targetSpace, sources, at) -> {
                    if (!evidenceAllowed.get()) throw new AccessDeniedAsNotFoundException();
                }, (user, targetSpace, solutionId, action, at) -> {
                    if (!allowed.get()) throw new AccessDeniedAsNotFoundException();
                }, transactions, command -> { audit.add(command); return null; },
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void savesImmutableCandidateAndDoesNotInheritSupportedVerdict() {
        var service = service();
        var candidate = service.save(actor, initial.id(), section, baseline,
                null, initial.revision(), "新的事实说明", "edit-1");
        assertThat(candidate.kind()).isEqualTo(SectionVersionKind.HUMAN_EDIT);
        assertThat(candidate.parentVersionId()).contains(baseline);
        assertThat(candidate.baselineSectionVersionId()).contains(baseline);
        assertThat(candidate.verification().verifications()).singleElement().satisfies(check ->
                assertThat(check.verdict()).isEqualTo(CitationVerdict.HUMAN_CONFIRMATION_REQUIRED));
        assertThat(candidate.evidenceIds()).containsExactly(evidence);
        assertThat(initial.adoptedSectionVersionIds()).containsEntry(section, baseline);
        assertThat(service.save(actor, initial.id(), section, baseline,
                null, initial.revision(), "新的事实说明", "edit-1").id()).isEqualTo(candidate.id());
        assertThatThrownBy(() -> service.save(actor, initial.id(), section, baseline,
                null, initial.revision(), "复用键改正文", "edit-1"))
                .isInstanceOf(SolutionCommandConflictException.class);
        assertThatThrownBy(() -> service.save(actor, initial.id(), section, baseline,
                null, initial.revision(), "并发覆盖", "edit-2"))
                .isInstanceOf(ConcurrentSolutionUpdateException.class);
        var next = service.save(actor, initial.id(), section, baseline,
                candidate.id(), initial.revision(), "第二次修改", "edit-2");
        assertThat(next.parentVersionId()).contains(candidate.id());
        assertThat(service.save(actor, initial.id(), section, baseline,
                null, initial.revision() - 1, "新的事实说明", "edit-1").id()).isEqualTo(candidate.id());
        assertThat(audit).isNotEmpty();
    }

    @Test void revokedEvidenceAndStaleRevisionCannotCreateCandidate() {
        var service = service();
        allowed.set(false);
        assertThatThrownBy(() -> service.save(actor, initial.id(), section, baseline,
                null, initial.revision(), "不可读取", "edit-denied"))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        allowed.set(true);
        evidenceAllowed.set(false);
        assertThatThrownBy(() -> service.save(actor, initial.id(), section, baseline,
                null, initial.revision(), "证据已撤权", "edit-revoked"))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        evidenceAllowed.set(true);
        assertThatThrownBy(() -> service.save(actor, initial.id(), section, baseline,
                null, initial.revision() - 1, "过期版本", "edit-stale"))
                .isInstanceOf(ConcurrentSolutionUpdateException.class);
    }
}
