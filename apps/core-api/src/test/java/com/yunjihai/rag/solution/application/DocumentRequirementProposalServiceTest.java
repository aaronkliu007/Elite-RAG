package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.*;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.generation.ModelUsage;
import com.yunjihai.rag.solution.model.*;
import com.yunjihai.rag.solution.port.*;
import com.yunjihai.rag.solution.proposal.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class DocumentRequirementProposalServiceTest {
    private static final UUID ACTOR = id(1), SPACE = id(2), SOLUTION = id(3), VERSION = id(4);
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private static final DocumentVersionRef DOCUMENT = new DocumentVersionRef(id(5), id(6));
    private static UUID id(long n) { return new UUID(0, n); }

    @Test void generatesOnceWithPersistedEvidenceAndRechecksAccessOnReplay() {
        var f = new Fixture();
        var input = f.input();
        var result = f.service.generate(input);
        assertThat(result.status()).isEqualTo(ProposalStatus.SUCCEEDED);
        assertThat(result.resultJson().orElseThrow()).contains("提取的需求", DOCUMENT.documentId().toString());
        assertThat(result.resultJson().orElseThrow()).contains("evidenceIds", f.evidence.evidenceId().toString());
        assertThat(f.snapshots.snapshot).isNotNull();
        assertThat(f.service.generate(input).id()).isEqualTo(result.id());
        assertThat(f.service.find(input).id()).isEqualTo(result.id());
        assertThat(f.modelCalls).hasValue(1);
        assertThat(f.retrievalCalls).hasValue(1);
        assertThat(f.solution.revision()).isZero();
        f.allowed = false;
        assertThatThrownBy(() -> f.service.find(input)).isInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test void rejectsRevocationAfterModelAndForgedSourcesBeforeSuccess() {
        var revoked = new Fixture();
        revoked.revokeOnModel = true;
        assertThatThrownBy(() -> revoked.service.generate(revoked.input())).isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThat(revoked.attempt.status()).isNotEqualTo(ProposalStatus.SUCCEEDED);
        var forged = new Fixture();
        forged.forgeSource = true;
        assertThatThrownBy(() -> forged.service.generate(forged.input())).isInstanceOf(SolutionLifecycleException.class)
                .hasMessage("MODEL_PROPOSAL_INVALID");
        assertThat(forged.attempt.status()).isEqualTo(ProposalStatus.FAILED);
    }

    @Test void noEvidenceDoesNotCallModelAndTimeoutNeverRetriesSameKey() {
        var empty = new Fixture();
        empty.empty = true;
        assertThatThrownBy(() -> empty.service.generate(empty.input())).isInstanceOf(SolutionLifecycleException.class)
                .hasMessage("DOCUMENT_EVIDENCE_NOT_FOUND");
        assertThat(empty.modelCalls).hasValue(0);
        assertThat(empty.attempt.status()).isEqualTo(ProposalStatus.FAILED);
        var uncertain = new Fixture();
        uncertain.modelFailure = true;
        assertThatThrownBy(() -> uncertain.service.generate(uncertain.input())).isInstanceOf(SolutionLifecycleException.class)
                .hasMessage("MODEL_PROPOSAL_UNCERTAIN");
        assertThat(uncertain.service.generate(uncertain.input()).status()).isEqualTo(ProposalStatus.RUNNING);
        assertThat(uncertain.modelCalls).hasValue(1);
    }

    @Test void retrievalFailureDoesNotExposePrivateDetails() {
        var f = new Fixture();
        f.retrievalFailure = true;
        assertThatThrownBy(() -> f.service.generate(f.input())).isInstanceOf(SolutionLifecycleException.class)
                .hasMessage("DOCUMENT_EVIDENCE_UNAVAILABLE").hasNoCause();
        assertThat(f.attempt.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(f.modelCalls).hasValue(0);
    }

    @Test void refusesAnAmbientTransactionBeforeRegisteringOrCallingModel() {
        var f = new Fixture();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> f.service.generate(f.input())).isInstanceOf(SolutionLifecycleException.class)
                    .hasMessage("DOCUMENT_PROPOSAL_TRANSACTION_FORBIDDEN");
            assertThat(f.attempt).isNull();
            assertThat(f.modelCalls).hasValue(0);
            assertThat(f.retrievalCalls).hasValue(0);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test void adoptionRechecksEverySelectedDocumentEvenWhenCandidateHasNoSources() {
        var f = new Fixture();
        f.unknownNoSources = true;
        f.service.generate(f.input());
        var calls = new AtomicInteger();
        var adoption = f.adoption(calls, false, new FakeTransactions());
        f.allowed = false;
        assertThatThrownBy(() -> adoption.adopt(f.input(), "adoption-key"))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThat(calls).hasValue(0);
    }

    @Test void evidencePreviewRequiresCurrentAccessAndExactSnapshottedContent() {
        var f = new Fixture();
        f.service.generate(f.input());
        var preview = new DocumentRequirementEvidencePreviewService(f.service, f.snapshots,
                (space, document, version, node) -> Optional.of("建设知识库"));
        var result = preview.preview(f.input(), f.evidence.evidenceId());
        assertThat(result.text()).isEqualTo("建设知识库");
        assertThat(result.toString()).doesNotContain("建设知识库");
        var changed = new DocumentRequirementEvidencePreviewService(f.service, f.snapshots,
                (space, document, version, node) -> Optional.of("文档内容已变化"));
        assertThatThrownBy(() -> changed.preview(f.input(), f.evidence.evidenceId()))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        var revoked = new DocumentRequirementEvidencePreviewService(f.service, f.snapshots,
                (space, document, version, node) -> { f.allowed = false; return Optional.of("建设知识库"); });
        assertThatThrownBy(() -> revoked.preview(f.input(), f.evidence.evidenceId()))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test void adoptionUsesSavedCandidateInsideTransactionAndRollsBackOnRevocation() {
        var f = new Fixture();
        f.service.generate(f.input());
        var calls = new AtomicInteger();
        var transactions = new FakeTransactions();
        var adoption = f.adoption(calls, true, transactions);
        assertThatThrownBy(() -> adoption.adopt(f.input(), "adoption-key"))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThat(calls).hasValue(1);
        assertThat(transactions.rollbacks).hasValue(1);
    }

    private static final class Fixture {
        final RequirementVersion version = new RequirementVersion(VERSION, 1, List.of(new RequirementItem(id(7),
                RequirementCategory.UNKNOWN, "待明确", List.of())), ACTOR, NOW, "初始录入");
        final Solution solution = Solution.create(SOLUTION, SPACE, ACTOR, version, NOW);
        final Evidence evidence = new Evidence(SPACE, id(8), DOCUMENT.documentId(), DOCUMENT.versionId(), "node-1",
                "客户需求", "建设知识库", 0.9, new MarkdownLocator(List.of("范围"), "node-1"));
        final AtomicInteger modelCalls = new AtomicInteger(), retrievalCalls = new AtomicInteger();
        boolean allowed = true, revokeOnModel, forgeSource, empty, modelFailure, retrievalFailure, unknownNoSources;
        ProposalAttempt attempt;
        final Snapshots snapshots = new Snapshots();
        final DocumentRequirementProposalService service;
        final SolutionRepository solutions;
        final SolutionDocumentSourcePort sourceAccess;
        final ProposalAttemptStore store;
        Fixture() {
            solutions = new SolutionRepository() {
                public Optional<Solution> findById(UUID id) { return SOLUTION.equals(id) ? Optional.of(solution) : Optional.empty(); }
                public void insert(Solution value) { throw new AssertionError(); }
                public void replace(Solution value, long revision) { throw new AssertionError(); }
            };
            RequirementVersionRepository requirements = new RequirementVersionRepository() {
                public Optional<RequirementVersion> findById(UUID id) { return VERSION.equals(id) ? Optional.of(version) : Optional.empty(); }
                public void insert(RequirementVersion value) { throw new AssertionError(); }
            };
            sourceAccess = (actor, space, document, docVersion, at) -> allowed;
            var requirementSources = new SolutionSourceAccess((actor, space, at) -> {}, sourceAccess);
            var evidenceService = new DocumentRequirementEvidenceService((query, selected) -> {
                retrievalCalls.incrementAndGet();
                if (retrievalFailure) throw new IllegalStateException("private provider secret and customer text");
                var values = empty ? List.<Evidence>of() : List.of(evidence);
                return new EvidencePack(query.requestId(), values, false, Set.of(), new RetrievalStats(0, 0, 0, 0, values.size()), NOW);
            }, sourceAccess, Clock.fixed(NOW, ZoneOffset.UTC));
            store = new ProposalAttemptStore() {
                public ProposalRegistration register(ProposalRegistrationRequest request, Duration lease) {
                    if (attempt != null) {
                        if (!attempt.request().equals(request)) throw new ProposalRequestConflictException();
                        return new ProposalRegistration(attempt, false);
                    }
                    attempt = new ProposalAttempt(id(10), request, ProposalStatus.RUNNING, Optional.empty(), Optional.empty(),
                            NOW, NOW, NOW.plus(lease));
                    return new ProposalRegistration(attempt, true);
                }
                public Optional<ProposalAttempt> findByKey(ProposalKey key) { return Optional.ofNullable(attempt); }
                public Optional<ProposalAttempt> findCurrent(ProposalRegistrationRequest request) {
                    return Optional.ofNullable(attempt).filter(value -> value.request().equals(request));
                }
                public boolean succeed(ProposalRegistrationRequest request, String json) {
                    attempt = new ProposalAttempt(id(10), request, ProposalStatus.SUCCEEDED, Optional.of(json), Optional.empty(),
                            NOW, NOW, attempt.leaseUntil()); return true;
                }
                public boolean fail(ProposalRegistrationRequest request, String code) {
                    attempt = new ProposalAttempt(id(10), request, ProposalStatus.FAILED, Optional.empty(), Optional.of(code),
                            NOW, NOW, attempt.leaseUntil()); return true;
                }
                public int expireStale(int limit) { return 0; }
            };
            SolutionModelPort model = new SolutionModelPort() {
                public <T> SolutionModelResponse<T> execute(SolutionModelRequest request, Class<T> type) {
                    modelCalls.incrementAndGet();
                    assertThat(snapshots.snapshot).isNotNull();
                    assertThat(request.prompt()).isEqualTo(new PromptRef("requirements", "v2"));
                    if (modelFailure) throw new IllegalStateException("provider private detail");
                    if (revokeOnModel) allowed = false;
                    var ref = forgeSource ? new DocumentVersionRef(id(90), id(91)) : DOCUMENT;
                    var proposal = new GeneratedRequirementProposal(List.of(new GeneratedRequirementProposal.Item(id(12),
                            unknownNoSources ? "UNKNOWN" : "GOAL", "提取的需求",
                            unknownNoSources ? List.of() : List.of(new RequirementSource(ref, evidence.locator())))));
                    return new SolutionModelResponse<>(type, type.cast(proposal), "model", new ModelUsage(1, 1, 0, 1));
                }
            };
            service = new DocumentRequirementProposalService(solutions, requirements, SolutionAuthorizationPort.allowAll(),
                    requirementSources, sourceAccess, evidenceService, store, snapshots, model, Clock.fixed(NOW, ZoneOffset.UTC), true);
        }
        DocumentRequirementProposalService.Command input() {
            return new DocumentRequirementProposalService.Command(ACTOR, SOLUTION, 0, "key", "客户需求", List.of(DOCUMENT));
        }
        DocumentRequirementProposalAdoptionService adoption(AtomicInteger calls, boolean revokeAfterSave,
                FakeTransactions transactions) {
            SolutionCommandPort commands = new SolutionCommandPort() {
                public SolutionCommandResult create(UUID actor, String key, SolutionCommandService.CreateInput input) { throw new AssertionError(); }
                public SolutionCommandResult reviseRequirements(UUID actor, String key, SolutionCommandService.ReviseRequirementsInput input) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(input.items()).singleElement().satisfies(item -> assertThat(item.text()).isEqualTo("提取的需求"));
                    calls.incrementAndGet(); if (revokeAfterSave) allowed = false;
                    return new SolutionCommandResult(SOLUTION, 1, id(20), Optional.empty(), SolutionPhase.REQUIREMENTS_DRAFT);
                }
                public SolutionCommandResult confirmRequirements(UUID actor, String key, SolutionCommandService.ConfirmInput input) { throw new AssertionError(); }
                public SolutionCommandResult reviseOutline(UUID actor, String key, SolutionCommandService.ReviseOutlineInput input) { throw new AssertionError(); }
                public SolutionCommandResult confirmOutline(UUID actor, String key, SolutionCommandService.ConfirmInput input) { throw new AssertionError(); }
            };
            return new DocumentRequirementProposalAdoptionService(solutions, SolutionAuthorizationPort.allowAll(),
                    sourceAccess, store, snapshots, commands, new TransactionTemplate(transactions), Clock.fixed(NOW, ZoneOffset.UTC),
                    com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher.noop(), actor -> { });
        }
    }
    private static final class FakeTransactions extends AbstractPlatformTransactionManager {
        final AtomicInteger rollbacks = new AtomicInteger();
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks.incrementAndGet(); }
    }
    private static final class Snapshots implements ProposalEvidenceStore {
        ProposalEvidenceSnapshot snapshot;
        public void save(UUID id, ProposalEvidenceSnapshot value) { snapshot = value; }
        public Optional<ProposalEvidenceSnapshot> find(UUID id) { return Optional.ofNullable(snapshot); }
    }
}
