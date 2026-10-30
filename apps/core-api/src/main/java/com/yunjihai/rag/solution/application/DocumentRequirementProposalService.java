package com.yunjihai.rag.solution.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.model.*;
import com.yunjihai.rag.solution.port.*;
import com.yunjihai.rag.solution.proposal.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Coordinates document evidence and a read-only model candidate. */
public final class DocumentRequirementProposalService implements DocumentRequirementProposalPort {
    private static final Duration LEASE = Duration.ofSeconds(90);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SolutionRepository solutions;
    private final RequirementVersionRepository requirements;
    private final SolutionAuthorizationPort authorization;
    private final SolutionSourceAccess requirementSources;
    private final SolutionDocumentSourcePort documents;
    private final DocumentRequirementEvidenceService evidence;
    private final ProposalAttemptStore attempts;
    private final ProposalEvidenceStore snapshots;
    private final SolutionModelPort model;
    private final Clock clock;
    private final boolean modelEnabled;

    public DocumentRequirementProposalService(SolutionRepository solutions, RequirementVersionRepository requirements,
            SolutionAuthorizationPort authorization, SolutionSourceAccess requirementSources,
            SolutionDocumentSourcePort documents, DocumentRequirementEvidenceService evidence,
            ProposalAttemptStore attempts, ProposalEvidenceStore snapshots, SolutionModelPort model,
            Clock clock, boolean modelEnabled) {
        this.solutions = Objects.requireNonNull(solutions); this.requirements = Objects.requireNonNull(requirements);
        this.authorization = Objects.requireNonNull(authorization); this.requirementSources = Objects.requireNonNull(requirementSources);
        this.documents = Objects.requireNonNull(documents); this.evidence = Objects.requireNonNull(evidence);
        this.attempts = Objects.requireNonNull(attempts); this.snapshots = Objects.requireNonNull(snapshots);
        this.model = Objects.requireNonNull(model); this.clock = Objects.requireNonNull(clock); this.modelEnabled = modelEnabled;
    }

    public ProposalAttempt find(Command command) {
        var context = context(command);
        var request = registration(command, context);
        var attempt = attempts.findCurrent(request).orElseThrow(AccessDeniedAsNotFoundException::new);
        verifyReadable(command, context, attempt);
        return attempt;
    }

    public ProposalAttempt generate(Command command) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new SolutionLifecycleException("DOCUMENT_PROPOSAL_TRANSACTION_FORBIDDEN");
        if (!modelEnabled) throw new SolutionLifecycleException("MODEL_PROPOSAL_UNAVAILABLE");
        var context = context(command);
        var request = registration(command, context);
        var registration = attempts.register(request, LEASE);
        if (!registration.created()) {
            verifyReadable(command, context, registration.attempt());
            return registration.attempt();
        }
        var attemptId = registration.attempt().id();
        final DocumentRequirementEvidenceService.Selection selection;
        final ProposalEvidenceSnapshot snapshot;
        try {
            selection = evidence.select(attemptId, command.actorId(), context.spaceId(), context.query(), command.documents());
            if (selection.evidence().isEmpty()) {
                attempts.fail(request, "DOCUMENT_EVIDENCE_NOT_FOUND");
                throw new SolutionLifecycleException("DOCUMENT_EVIDENCE_NOT_FOUND");
            }
            snapshot = ProposalEvidenceSnapshot.from(context.spaceId(), command.documents(), selection.evidence());
            snapshots.save(attemptId, snapshot);
            ensureSameContext(command, context);
        } catch (AccessDeniedAsNotFoundException | SolutionAuthorityUnavailableException
                | SolutionLifecycleException | ProposalRequestConflictException failure) {
            if (!(failure instanceof SolutionLifecycleException gate && "DOCUMENT_EVIDENCE_NOT_FOUND".equals(gate.errorCode())))
                attempts.fail(request, "DOCUMENT_EVIDENCE_UNAVAILABLE");
            throw failure;
        } catch (RuntimeException failure) {
            attempts.fail(request, "DOCUMENT_EVIDENCE_UNAVAILABLE");
            throw new SolutionLifecycleException("DOCUMENT_EVIDENCE_UNAVAILABLE");
        }
        var modelRequest = new SolutionModelRequest(attemptId, command.clientKey(), ModelScenario.REQUIREMENTS_EXTRACTION,
                new PromptRef("requirements", "v2"), List.of(), Optional.empty(), List.of("仅引用输入证据"),
                selection.evidence(), clock.instant().plusSeconds(60));
        final String result;
        try {
            var response = model.execute(modelRequest, GeneratedRequirementProposal.class);
            var allowed = selection.evidence().stream().map(item -> new RequirementSource(
                    new DocumentVersionRef(item.documentId(), item.versionId()), item.locator())).collect(Collectors.toUnmodifiableSet());
            var json = SolutionProposalService.requirementsJson(response.body(), context.version(), command.expectedRevision(), allowed);
            var payload = (ObjectNode) JSON.readTree(json);
            var proposedItems = response.body().items();
            for (int i = 0; i < proposedItems.size(); i++) {
                var proposedSources = proposedItems.get(i).sources();
                for (int j = 0; j < proposedSources.size(); j++) {
                    var source = proposedSources.get(j);
                    var target = (ObjectNode) payload.path("items").get(i).path("sources").get(j);
                    var ids = target.putArray("evidenceIds");
                    snapshot.sources().stream().filter(item -> item.document().equals(source.documentVersion())
                                    && item.locator().equals(source.locator()))
                            .forEach(item -> ids.add(item.evidenceId().toString()));
                    if (ids.isEmpty()) throw new IllegalArgumentException("missing evidence identity");
                }
            }
            payload.set("documentsWithoutEvidence", JSON.valueToTree(selection.documentsWithoutEvidence()));
            payload.put("retrievalDegraded", selection.degraded());
            result = JSON.writeValueAsString(payload);
        } catch (ModelGatewayException gateway) {
            if (gateway.kind() == ModelGatewayException.Kind.TIMEOUT || gateway.kind() == ModelGatewayException.Kind.UNAVAILABLE)
                throw new SolutionLifecycleException("MODEL_PROPOSAL_UNCERTAIN");
            attempts.fail(request, "MODEL_GATEWAY_" + gateway.kind().name());
            throw new SolutionLifecycleException(gateway.kind() == ModelGatewayException.Kind.INVALID_RESPONSE
                    ? "MODEL_PROPOSAL_INVALID" : "MODEL_PROPOSAL_REJECTED");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            attempts.fail(request, "MODEL_OUTPUT_INVALID");
            throw new SolutionLifecycleException("MODEL_PROPOSAL_INVALID");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            attempts.fail(request, "MODEL_OUTPUT_INVALID");
            throw new SolutionLifecycleException("MODEL_PROPOSAL_INVALID");
        } catch (RuntimeException uncertain) {
            throw new SolutionLifecycleException("MODEL_PROPOSAL_UNCERTAIN");
        }
        try { ensureSameContext(command, context); }
        catch (RuntimeException changed) { attempts.fail(request, "CONTEXT_UNAVAILABLE"); throw changed; }
        if (!attempts.succeed(request, result)) throw new SolutionLifecycleException("MODEL_PROPOSAL_LEASE_EXPIRED");
        var attempt = attempts.findByKey(request.key()).orElseThrow(AccessDeniedAsNotFoundException::new);
        verifyReadable(command, context, attempt);
        return attempt;
    }

    private Context context(Command command) {
        Objects.requireNonNull(command);
        var solution = solutions.findById(command.solutionId()).orElseThrow(AccessDeniedAsNotFoundException::new);
        var at = clock.instant();
        authorization.require(command.actorId(), solution.spaceId(), solution.id(), SolutionAction.EDIT_REQUIREMENTS, at);
        if (solution.revision() != command.expectedRevision()) throw new ConcurrentSolutionUpdateException();
        var version = requirements.findById(solution.currentRequirementVersionId()).orElseThrow(AccessDeniedAsNotFoundException::new);
        requirementSources.requireCurrentSources(command.actorId(), solution.spaceId(), version, at);
        requireDocuments(command, solution.spaceId(), at);
        var query = new RetrievalQuery(UUID.randomUUID(), command.actorId(), Set.of(solution.spaceId()), command.query(), Map.of(), 15).queryText();
        return new Context(solution.spaceId(), version, query);
    }

    private void requireDocuments(Command command, UUID space, Instant at) {
        for (var document : command.documents()) {
            final boolean readable;
            try { readable = documents.isCurrentPublishedAndReadable(command.actorId(), space,
                    document.documentId(), document.versionId(), at); }
            catch (AccessDeniedAsNotFoundException denied) { throw denied; }
            catch (RuntimeException unavailable) { throw new SolutionAuthorityUnavailableException(); }
            if (!readable) throw new AccessDeniedAsNotFoundException();
        }
    }

    private ProposalRegistrationRequest registration(Command command, Context context) {
        var key = new ProposalKey(command.actorId(), command.solutionId(), ProposalKind.DOCUMENT_REQ, command.clientKey());
        return new ProposalRegistrationRequest(key, context.version().id(), command.expectedRevision(),
                SolutionRequestDigest.documentProposal(command.solutionId(), command.expectedRevision(), context.version().id(),
                        context.query(), command.documents()));
    }

    private void ensureSameContext(Command command, Context original) {
        var current = context(command);
        if (!current.spaceId().equals(original.spaceId()) || !current.version().equals(original.version()))
            throw new SolutionLifecycleException("MODEL_PROPOSAL_STALE");
    }

    private void verifyReadable(Command command, Context original, ProposalAttempt attempt) {
        if (attempt.status() == ProposalStatus.SUCCEEDED) {
            var snapshot = snapshots.find(attempt.id()).orElseThrow(AccessDeniedAsNotFoundException::new);
            if (!snapshot.spaceId().equals(original.spaceId()) || !snapshot.documents().equals(command.documents()))
                throw new AccessDeniedAsNotFoundException();
        }
        ensureSameContext(command, original);
    }

    public record Command(UUID actorId, UUID solutionId, long expectedRevision, String clientKey,
            String query, List<DocumentVersionRef> documents) {
        public Command {
            Objects.requireNonNull(actorId); Objects.requireNonNull(solutionId);
            if (expectedRevision < 0 || clientKey == null || clientKey.isBlank() || query == null)
                throw new IllegalArgumentException("invalid document proposal command");
            documents = List.copyOf(Objects.requireNonNull(documents));
            if (documents.isEmpty() || documents.size() > 10 || new HashSet<>(documents).size() != documents.size())
                throw new IllegalArgumentException("invalid document selection");
        }
        @Override public String toString() { return "Command[actorId=" + actorId + ", solutionId=" + solutionId
                + ", documentCount=" + documents.size() + "]"; }
    }
    private record Context(UUID spaceId, RequirementVersion version, String query) { }
}
