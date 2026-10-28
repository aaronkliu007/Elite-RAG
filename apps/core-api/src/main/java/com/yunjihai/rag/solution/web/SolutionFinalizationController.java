package com.yunjihai.rag.solution.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.solution.application.SolutionAccessService;
import com.yunjihai.rag.solution.application.SolutionSourceAccess;
import com.yunjihai.rag.solution.application.SolutionRiskConfirmationCommandService;
import com.yunjihai.rag.solution.domain.Solution;
import com.yunjihai.rag.solution.finalization.EvidenceAccessPort;
import com.yunjihai.rag.solution.finalization.SolutionFinalizationService;
import com.yunjihai.rag.solution.finalization.SolutionWordExportService;
import com.yunjihai.rag.solution.generation.SectionVersionRepository;
import com.yunjihai.rag.solution.port.OutlineVersionRepository;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import com.yunjihai.rag.solution.verification.CitationVerdict;
import com.yunjihai.rag.solution.verification.RiskConfirmationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "rag.opensearch.enabled", havingValue = "true")
public final class SolutionFinalizationController {
    private final SolutionFinalizationService finalization;
    private final SolutionRiskConfirmationCommandService riskCommands;
    private final SolutionWordExportService export;
    private final SolutionRepository solutions;
    private final RequirementVersionRepository requirements;
    private final OutlineVersionRepository outlines;
    private final SectionVersionRepository sections;
    private final RiskConfirmationRepository confirmations;
    private final SolutionAccessService access;
    private final SolutionSourceAccess sources;
    private final EvidenceAccessPort evidenceAccess;
    private final Clock clock;

    public SolutionFinalizationController(SolutionFinalizationService finalization,
            SolutionRiskConfirmationCommandService riskCommands,
            SolutionWordExportService export, SolutionRepository solutions,
            RequirementVersionRepository requirements, OutlineVersionRepository outlines,
            SectionVersionRepository sections, RiskConfirmationRepository confirmations,
            SolutionAccessService access, SolutionSourceAccess sources,
            EvidenceAccessPort evidenceAccess, Clock clock) {
        this.finalization = Objects.requireNonNull(finalization);
        this.riskCommands = Objects.requireNonNull(riskCommands);
        this.export = Objects.requireNonNull(export);
        this.solutions = Objects.requireNonNull(solutions);
        this.requirements = Objects.requireNonNull(requirements);
        this.outlines = Objects.requireNonNull(outlines);
        this.sections = Objects.requireNonNull(sections);
        this.confirmations = Objects.requireNonNull(confirmations);
        this.access = Objects.requireNonNull(access);
        this.sources = Objects.requireNonNull(sources);
        this.evidenceAccess = Objects.requireNonNull(evidenceAccess);
        this.clock = Objects.requireNonNull(clock);
    }

    @GetMapping("/api/v1/solutions/{solutionId}/finalization-review")
    public ReviewView review(Authentication authentication, @PathVariable UUID solutionId) {
        var actorId = actor(authentication);
        var solution = authorizedSolution(actorId, solutionId);
        var outline = solution.currentOutlineVersionId().flatMap(outlines::findById)
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        var currentConfirmations = confirmations.findBySolutionId(solutionId);
        var risks = new ArrayList<RiskView>();
        var evidence = new HashSet<UUID>();
        for (var node : outline.nodes()) {
            var adoptedId = solution.adoptedSectionVersionIds().get(node.sectionId());
            if (adoptedId == null) continue;
            var section = sections.findById(adoptedId).orElseThrow(AccessDeniedAsNotFoundException::new);
            if (!section.solutionId().equals(solutionId)
                    || !section.requirementVersionId().equals(solution.currentRequirementVersionId())
                    || !section.outlineVersionId().equals(outline.id())) {
                throw new AccessDeniedAsNotFoundException();
            }
            evidence.addAll(section.evidenceIds());
            for (var verification : section.verification().verifications()) {
                if (verification.verdict() != CitationVerdict.HUMAN_CONFIRMATION_REQUIRED) continue;
                var claim = section.claims().stream()
                        .filter(item -> item.claimId().equals(verification.claimId()))
                        .findFirst().orElseThrow(AccessDeniedAsNotFoundException::new);
                var confirmation = currentConfirmations.stream()
                        .filter(item -> item.claimId().equals(claim.claimId()) && item.matches(section))
                        .findFirst();
                risks.add(new RiskView(node.sectionId(), node.title(), section.id(), claim.claimId(),
                        claim.text(), claim.riskTypes().stream().map(Enum::name).sorted().toList(),
                        confirmation.isPresent(), confirmation.map(item -> item.confirmedBy()).orElse(null),
                        confirmation.map(item -> item.confirmedAt()).orElse(null)));
            }
        }
        var allowed = evidenceAccess.authorize(actorId, solution.spaceId(), Set.copyOf(evidence), clock.instant());
        if (allowed == null || !allowed.equals(evidence)) throw new AccessDeniedAsNotFoundException();
        return new ReviewView(solution.id(), solution.revision(), List.copyOf(risks));
    }

    @PostMapping("/api/v1/solutions/{solutionId}/risk-confirmations")
    public ConfirmationView confirmRisk(Authentication authentication, @PathVariable UUID solutionId,
            @RequestBody RiskRequest request) {
        if (request == null || request.sectionVersionId() == null || request.claimId() == null) {
            throw new IllegalArgumentException("risk confirmation request is incomplete");
        }
        var result = riskCommands.confirm(actor(authentication), solutionId,
                request.sectionVersionId(), request.claimId(), request.reason(), clock.instant());
        return new ConfirmationView(result.id(), result.sectionVersionId(), result.claimId(),
                result.confirmedBy(), result.confirmedAt());
    }

    @PostMapping("/api/v1/solutions/{solutionId}/finalize")
    public FinalizedView finalizeSolution(Authentication authentication, @PathVariable UUID solutionId,
            @RequestBody FinalizeRequest request) {
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new IllegalArgumentException("expectedRevision is required");
        }
        var result = finalization.finalizeSolution(actor(authentication), solutionId,
                request.expectedRevision(), clock.instant());
        return new FinalizedView(result.id(), result.solutionRevision(), result.contentHash(), result.createdAt());
    }

    @GetMapping("/api/v1/solutions/{solutionId}/finalized-versions/{versionId}/word")
    public ResponseEntity<byte[]> downloadWord(Authentication authentication,
            @PathVariable UUID solutionId, @PathVariable UUID versionId) {
        var bytes = export.export(actor(authentication), solutionId, versionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=solution-" + versionId + ".docx")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(bytes.length).body(bytes);
    }

    private Solution authorizedSolution(UUID actorId, UUID solutionId) {
        var solution = solutions.findById(solutionId).orElseThrow(AccessDeniedAsNotFoundException::new);
        var at = clock.instant();
        access.requireView(actorId, solution.spaceId(), at);
        var requirement = requirements.findById(solution.currentRequirementVersionId())
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        sources.requireCurrentSources(actorId, solution.spaceId(), requirement, at);
        return solution;
    }

    private static UUID actor(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AuthenticationCredentialsNotFoundException("login required");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            throw new AuthenticationCredentialsNotFoundException("invalid principal");
        }
    }

    public record RiskRequest(UUID sectionVersionId, UUID claimId, String reason) { }
    public record FinalizeRequest(Long expectedRevision) { }
    public record ConfirmationView(UUID id, UUID sectionVersionId, UUID claimId,
            UUID confirmedBy, Instant confirmedAt) { }
    public record RiskView(UUID sectionId, String sectionTitle, UUID sectionVersionId,
            UUID claimId, String claimText, List<String> riskTypes, boolean confirmed,
            UUID confirmedBy, Instant confirmedAt) { }
    public record ReviewView(UUID solutionId, long revision, List<RiskView> risks) { }
    public record FinalizedView(UUID id, long solutionRevision, String contentHash, Instant createdAt) { }
}
