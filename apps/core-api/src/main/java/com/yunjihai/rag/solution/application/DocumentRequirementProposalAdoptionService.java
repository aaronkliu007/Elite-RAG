package com.yunjihai.rag.solution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.infrastructure.postgres.SolutionLocatorJson;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.RetrievalQuery;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import com.yunjihai.rag.solution.SolutionAction;
import com.yunjihai.rag.solution.SolutionAuthorizationPort;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.proposal.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

/** Revalidates the saved evidence and adopts only the server-stored candidate in one transaction. */
public final class DocumentRequirementProposalAdoptionService implements DocumentRequirementProposalAdoptionPort {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SolutionRepository solutions;
    private final SolutionAuthorizationPort authorization;
    private final SolutionDocumentSourcePort documents;
    private final ProposalAttemptStore attempts;
    private final ProposalEvidenceStore snapshots;
    private final SolutionCommandPort commands;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ProjectionChangePublisher locks;
    private final IdentityAuthorizationLock identityLock;

    public DocumentRequirementProposalAdoptionService(SolutionRepository solutions, SolutionAuthorizationPort authorization,
            SolutionDocumentSourcePort documents, ProposalAttemptStore attempts, ProposalEvidenceStore snapshots,
            SolutionCommandPort commands, TransactionTemplate transactions, Clock clock, ProjectionChangePublisher locks,
            IdentityAuthorizationLock identityLock) {
        this.solutions = Objects.requireNonNull(solutions); this.authorization = Objects.requireNonNull(authorization);
        this.documents = Objects.requireNonNull(documents); this.attempts = Objects.requireNonNull(attempts);
        this.snapshots = Objects.requireNonNull(snapshots); this.commands = Objects.requireNonNull(commands);
        this.transactions = Objects.requireNonNull(transactions); this.clock = Objects.requireNonNull(clock);
        this.locks = Objects.requireNonNull(locks);
        this.identityLock = Objects.requireNonNull(identityLock);
    }

    public SolutionCommandResult adopt(DocumentRequirementProposalService.Command command, String adoptionKey) {
        Objects.requireNonNull(command);
        if (adoptionKey == null || adoptionKey.isBlank()) throw new IllegalArgumentException("adoption key required");
        return Objects.requireNonNull(transactions.execute(status -> {
            identityLock.lock(command.actorId());
            var solution = solutions.findById(command.solutionId()).orElseThrow(AccessDeniedAsNotFoundException::new);
            var sortedDocuments = command.documents().stream().map(DocumentVersionRef::documentId).distinct().sorted().toList();
            return locks.withSpaceChangeLock(solution.spaceId(), () -> withDocumentLocks(sortedDocuments, 0,
                    () -> adoptLocked(command, adoptionKey, solution.spaceId())));
        }));
    }

    private SolutionCommandResult adoptLocked(DocumentRequirementProposalService.Command command, String adoptionKey, UUID spaceId) {
            requireDocuments(command, spaceId);
            var key = new ProposalKey(command.actorId(), command.solutionId(), ProposalKind.DOCUMENT_REQ, command.clientKey());
            var attempt = attempts.findByKey(key).orElseThrow(AccessDeniedAsNotFoundException::new);
            var input = new RetrievalQuery(UUID.randomUUID(), command.actorId(), Set.of(spaceId),
                    command.query(), Map.of(), 15);
            var expectedFingerprint = SolutionRequestDigest.documentProposal(command.solutionId(), command.expectedRevision(),
                    attempt.request().requirementVersionId(), input.queryText(), command.documents());
            if (attempt.status() != ProposalStatus.SUCCEEDED || attempt.request().expectedRevision() != command.expectedRevision()
                    || !attempt.request().inputFingerprint().equals(expectedFingerprint))
                throw new AccessDeniedAsNotFoundException();
            var snapshot = snapshots.find(attempt.id()).orElseThrow(AccessDeniedAsNotFoundException::new);
            if (!snapshot.spaceId().equals(spaceId) || !snapshot.documents().equals(command.documents()))
                throw new AccessDeniedAsNotFoundException();
            var items = items(attempt, snapshot);
            var result = commands.reviseRequirements(command.actorId(), adoptionKey,
                    new SolutionCommandService.ReviseRequirementsInput(command.solutionId(), items,
                            "采纳文档需求提取候选", command.expectedRevision()));
            requireDocuments(command, spaceId);
            return result;
    }

    private <T> T withDocumentLocks(List<UUID> documents, int index, java.util.function.Supplier<T> action) {
        if (index == documents.size()) return action.get();
        return locks.withDocumentChangeLock(documents.get(index), () -> withDocumentLocks(documents, index + 1, action));
    }

    private void requireDocuments(DocumentRequirementProposalService.Command command, UUID space) {
        var at = clock.instant();
        authorization.require(command.actorId(), space, command.solutionId(), SolutionAction.EDIT_REQUIREMENTS, at);
        for (var document : command.documents()) {
            final boolean readable;
            try { readable = documents.isCurrentPublishedAndReadable(command.actorId(), space,
                    document.documentId(), document.versionId(), at); }
            catch (AccessDeniedAsNotFoundException denied) { throw denied; }
            catch (RuntimeException unavailable) { throw new SolutionAuthorityUnavailableException(); }
            if (!readable) throw new AccessDeniedAsNotFoundException();
        }
    }

    private static List<RequirementItem> items(ProposalAttempt attempt, ProposalEvidenceSnapshot snapshot) {
        try {
            var root = JSON.readTree(attempt.resultJson().orElseThrow());
            if (!root.isObject() || !"REQUIREMENTS".equals(root.path("kind").asText())
                    || !attempt.request().requirementVersionId().toString().equals(root.path("requirementVersionId").asText())
                    || !root.path("expectedRevision").isIntegralNumber()
                    || root.path("expectedRevision").longValue() != attempt.request().expectedRevision())
                throw new IllegalArgumentException();
            var values = root.path("items");
            if (!values.isArray() || values.isEmpty() || values.size() > 100) throw new IllegalArgumentException();
            var allowed = snapshot.sources().stream().map(source -> new RequirementSource(source.document(), source.locator()))
                    .collect(Collectors.toUnmodifiableSet());
            var result = new ArrayList<RequirementItem>();
            for (JsonNode value : values) {
                var sources = new ArrayList<RequirementSource>();
                var sourceNodes = value.path("sources");
                if (!sourceNodes.isArray()) throw new IllegalArgumentException();
                for (var source : sourceNodes) {
                    var reference = new DocumentVersionRef(UUID.fromString(source.path("documentId").asText()),
                            UUID.fromString(source.path("versionId").asText()));
                    var item = new RequirementSource(reference, SolutionLocatorJson.read(source.path("locator").toString()));
                    if (!allowed.contains(item)) throw new IllegalArgumentException();
                    sources.add(item);
                }
                result.add(new RequirementItem(UUID.fromString(value.path("itemId").asText()),
                        RequirementCategory.valueOf(value.path("category").asText()), value.path("text").asText(), sources));
            }
            return List.copyOf(result);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new SolutionLifecycleException("MODEL_PROPOSAL_INVALID");
        }
    }
}
