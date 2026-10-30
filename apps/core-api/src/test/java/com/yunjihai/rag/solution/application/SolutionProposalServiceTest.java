package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.generation.ModelUsage;
import com.yunjihai.rag.solution.model.*;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.proposal.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SolutionProposalServiceTest {
    private static final UUID ACTOR = id(1), SPACE = id(2), SOLUTION = id(3), VERSION = id(4);
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    @Test
    void requirementsCandidateIsPersistedButDoesNotReviseSolutionAndSameKeyReplays() {
        var fixture = new Fixture();
        var command = new SolutionProposalService.Command(ACTOR, SOLUTION, 0, "request-1");

        var first = fixture.service.generateRequirements(command);
        var replay = fixture.service.generateRequirements(command);

        assertThat(first.status()).isEqualTo(ProposalStatus.SUCCEEDED);
        assertThat(first.resultJson().orElseThrow()).contains("整理后的需求", "documentId", "MARKDOWN");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(fixture.modelCalls).hasValue(1);
        assertThat(fixture.solution.revision()).isZero();
        assertThat(fixture.requirementInserts).isZero();
    }

    @Test
    void changedInputWithSameKeyConflictsBeforeSecondModelCall() {
        var fixture = new Fixture();
        fixture.service.generateRequirements(new SolutionProposalService.Command(ACTOR, SOLUTION, 0, "request-1"));
        fixture.currentVersion = new RequirementVersion(VERSION, 1, List.of(new RequirementItem(
                id(7), RequirementCategory.SCOPE, "内容发生变化", List.of(fixture.source))), ACTOR, NOW,
                "初始录入");

        assertThatThrownBy(() -> fixture.service.generateRequirements(
                new SolutionProposalService.Command(ACTOR, SOLUTION, 0, "request-1")))
                .isInstanceOf(ProposalRequestConflictException.class);
        assertThat(fixture.modelCalls).hasValue(1);
    }

    @Test
    void revokedSourceAfterModelCallCannotBecomeSuccessfulCandidate() {
        var fixture = new Fixture();
        fixture.revokeOnModel = true;

        assertThatThrownBy(() -> fixture.service.generateRequirements(
                new SolutionProposalService.Command(ACTOR, SOLUTION, 0, "request-2")))
                .isInstanceOf(RuntimeException.class);
        assertThat(fixture.store.saved.values()).singleElement()
                .satisfies(attempt -> assertThat(attempt.status()).isNotEqualTo(ProposalStatus.SUCCEEDED));
    }

    @Test
    void outlineRequiresConfirmedRequirementsAndThenOnlyStoresCandidate() {
        var fixture = new Fixture();
        assertThatThrownBy(() -> fixture.service.generateOutline(
                new SolutionProposalService.Command(ACTOR, SOLUTION, 0, "outline-1")))
                .isInstanceOf(SolutionLifecycleException.class)
                .hasMessage("REQUIREMENTS_NOT_CONFIRMED");
        assertThat(fixture.modelCalls).hasValue(0);
        fixture.solution = fixture.solution.confirmRequirements(id(30), NOW.plusSeconds(1), 0);

        var result = fixture.service.generateOutline(
                new SolutionProposalService.Command(ACTOR, SOLUTION, 1, "outline-1"));

        assertThat(result.status()).isEqualTo(ProposalStatus.SUCCEEDED);
        assertThat(result.resultJson().orElseThrow()).contains("OUTLINE", "方案概述");
        assertThat(fixture.solution.currentOutlineVersionId()).isEmpty();
    }

    @Test
    void disabledModelDoesNotRegisterOrCallProvider() {
        var fixture = new Fixture(false);
        assertThatThrownBy(() -> fixture.service.generateRequirements(
                new SolutionProposalService.Command(ACTOR, SOLUTION, 0, "disabled")))
                .isInstanceOf(SolutionLifecycleException.class)
                .hasMessage("MODEL_PROPOSAL_UNAVAILABLE");
        assertThat(fixture.store.saved).isEmpty();
        assertThat(fixture.modelCalls).hasValue(0);
    }

    @Test
    void uncertainModelFailureDoesNotRetrySameKey() {
        var fixture = new Fixture();
        fixture.modelFailure = new IllegalStateException("private provider detail");
        var command = new SolutionProposalService.Command(ACTOR, SOLUTION, 0, "uncertain");

        assertThatThrownBy(() -> fixture.service.generateRequirements(command))
                .isInstanceOf(SolutionLifecycleException.class)
                .hasMessage("MODEL_PROPOSAL_UNCERTAIN")
                .hasNoCause();
        var replay = fixture.service.generateRequirements(command);

        assertThat(replay.status()).isEqualTo(ProposalStatus.RUNNING);
        assertThat(fixture.modelCalls).hasValue(1);
        assertThat(fixture.store.saved).hasSize(1);
    }

    @Test
    void definitiveGatewayRejectionsAreFailedInsteadOfIndeterminate() {
        for (var kind : List.of(ModelGatewayException.Kind.INVALID_RESPONSE,
                ModelGatewayException.Kind.POLICY_VIOLATION)) {
            var fixture = new Fixture();
            fixture.modelFailure = new ModelGatewayException(kind);
            var command = new SolutionProposalService.Command(ACTOR, SOLUTION, 0, "rejected");
            assertThatThrownBy(() -> fixture.service.generateRequirements(command))
                    .isInstanceOf(SolutionLifecycleException.class).hasNoCause();
            assertThat(fixture.service.generateRequirements(command).status()).isEqualTo(ProposalStatus.FAILED);
            assertThat(fixture.modelCalls).hasValue(1);
        }
    }

    private static UUID id(long value) { return new UUID(0, value); }

    private static final class Fixture {
        private final RequirementSource source = new RequirementSource(
                new DocumentVersionRef(id(5), id(6)), new MarkdownLocator(List.of("需求"), "node-1"));
        private final RequirementItem item = new RequirementItem(id(7), RequirementCategory.SCOPE,
                "原始需求", List.of(source));
        private final RequirementVersion version = new RequirementVersion(VERSION, 1, List.of(item), ACTOR, NOW,
                "初始录入");
        private RequirementVersion currentVersion = version;
        private Solution solution = Solution.create(SOLUTION, SPACE, ACTOR, version, NOW);
        private final AtomicInteger modelCalls = new AtomicInteger();
        private int requirementInserts;
        private boolean revokeOnModel;
        private boolean sourceRevoked;
        private RuntimeException modelFailure;
        private final MemoryStore store = new MemoryStore();
        private final SolutionProposalService service;

        Fixture() { this(true); }

        Fixture(boolean modelEnabled) {
            SolutionRepository solutions = new SolutionRepository() {
                @Override public Optional<Solution> findById(UUID id) { return Optional.ofNullable(
                        SOLUTION.equals(id) ? solution : null); }
                @Override public void insert(Solution value) { throw new AssertionError("unexpected write"); }
                @Override public void replace(Solution value, long revision) { throw new AssertionError("unexpected write"); }
            };
            RequirementVersionRepository requirements = new RequirementVersionRepository() {
                @Override public Optional<RequirementVersion> findById(UUID id) { return Optional.ofNullable(
                        VERSION.equals(id) ? currentVersion : null); }
                @Override public void insert(RequirementVersion value) { requirementInserts++; }
            };
            var sources = new SolutionSourceAccess((actor, space, at) -> {},
                    (actor, space, document, documentVersion, at) -> !sourceRevoked);
            SolutionModelPort model = new SolutionModelPort() {
                @Override public <T> SolutionModelResponse<T> execute(SolutionModelRequest request, Class<T> type) {
                    modelCalls.incrementAndGet();
                    if (modelFailure != null) throw modelFailure;
                    if (revokeOnModel) sourceRevoked = true;
                    Object proposal = type == GeneratedOutlineProposal.class
                            ? new GeneratedOutlineProposal(List.of(new GeneratedOutlineProposal.Node(
                                    id(9), "方案概述", 1, 1, true, "概述", "需求", Set.of())))
                            : new GeneratedRequirementProposal(List.of(
                                    new GeneratedRequirementProposal.Item(id(8), "SCOPE", "整理后的需求", List.of(source))));
                    return new SolutionModelResponse<>(type, type.cast(proposal), "approved-model",
                            new ModelUsage(10, 5, 1, 30));
                }
            };
            service = new SolutionProposalService(solutions, requirements, SolutionAuthorizationPort.allowAll(),
                    sources, store, model, Clock.fixed(NOW, ZoneOffset.UTC), modelEnabled);
        }
    }

    private static final class MemoryStore implements ProposalAttemptStore {
        private final Map<ProposalKey, ProposalAttempt> saved = new HashMap<>();
        @Override public ProposalRegistration register(ProposalRegistrationRequest request, Duration lease) {
            var previous = saved.get(request.key());
            if (previous != null) {
                if (!previous.request().equals(request)) throw new ProposalRequestConflictException();
                return new ProposalRegistration(previous, false);
            }
            var attempt = new ProposalAttempt(id(20), request, ProposalStatus.RUNNING,
                    Optional.empty(), Optional.empty(), NOW, NOW, NOW.plus(lease));
            saved.put(request.key(), attempt);
            return new ProposalRegistration(attempt, true);
        }
        @Override public Optional<ProposalAttempt> findByKey(ProposalKey key) { return Optional.ofNullable(saved.get(key)); }
        @Override public boolean succeed(ProposalRegistrationRequest request, String json) {
            var previous = saved.get(request.key());
            saved.put(request.key(), new ProposalAttempt(previous.id(), request, ProposalStatus.SUCCEEDED,
                    Optional.of(json), Optional.empty(), NOW, NOW, previous.leaseUntil()));
            return true;
        }
        @Override public boolean fail(ProposalRegistrationRequest request, String code) {
            var previous = saved.get(request.key());
            saved.put(request.key(), new ProposalAttempt(previous.id(), request, ProposalStatus.FAILED,
                    Optional.empty(), Optional.of(code), NOW, NOW, previous.leaseUntil()));
            return true;
        }
        @Override public int expireStale(int batchSize) { return 0; }
    }
}
