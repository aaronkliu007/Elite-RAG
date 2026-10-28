package com.yunjihai.rag.solution.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.solution.application.*;
import com.yunjihai.rag.solution.domain.SectionVersion;
import com.yunjihai.rag.solution.domain.SectionVersionKind;
import com.yunjihai.rag.solution.finalization.EvidenceAccessPort;
import com.yunjihai.rag.solution.generation.GenerationEvidenceStore;
import com.yunjihai.rag.solution.generation.SectionVersionRepository;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

@RestController
@ConditionalOnBean(SolutionSectionEditingService.class)
public final class SolutionSectionEditingController {
    private final SolutionSectionEditingService editing;
    private final SolutionRepository solutions;
    private final SectionVersionRepository sections;
    private final SectionEditVersionQueryPort versions;
    private final RequirementVersionRepository requirements;
    private final SolutionAccessService access;
    private final SolutionSourceAccess sources;
    private final EvidenceAccessPort evidenceAccess;
    private final GenerationEvidenceStore evidenceSources;
    private final Clock clock;

    public SolutionSectionEditingController(SolutionSectionEditingService editing,
            SolutionRepository solutions, SectionVersionRepository sections,
            SectionEditVersionQueryPort versions, RequirementVersionRepository requirements,
            SolutionAccessService access, SolutionSourceAccess sources,
            EvidenceAccessPort evidenceAccess, GenerationEvidenceStore evidenceSources, Clock clock) {
        this.editing = Objects.requireNonNull(editing);
        this.solutions = Objects.requireNonNull(solutions);
        this.sections = Objects.requireNonNull(sections);
        this.versions = Objects.requireNonNull(versions);
        this.requirements = Objects.requireNonNull(requirements);
        this.access = Objects.requireNonNull(access);
        this.sources = Objects.requireNonNull(sources);
        this.evidenceAccess = Objects.requireNonNull(evidenceAccess);
        this.evidenceSources = Objects.requireNonNull(evidenceSources);
        this.clock = Objects.requireNonNull(clock);
    }

    @PostMapping("/api/v1/solutions/{solutionId}/sections/{sectionId}/edits")
    public VersionView edit(Authentication authentication, @PathVariable UUID solutionId,
            @PathVariable UUID sectionId, @RequestHeader("Idempotency-Key") String clientKey,
            @RequestBody EditRequest request) {
        if (request == null || request.baselineVersionId() == null || request.expectedRevision() == null) {
            throw new IllegalArgumentException("incomplete section edit request");
        }
        var actorId = actor(authentication);
        return VersionView.from(editing.save(actorId, solutionId, sectionId,
                request.baselineVersionId(), request.expectedDraftVersionId(),
                request.expectedRevision(), request.content(), clientKey));
    }

    @GetMapping("/api/v1/solutions/{solutionId}/sections/{sectionId}/versions")
    public List<VersionSummary> history(Authentication authentication, @PathVariable UUID solutionId,
            @PathVariable UUID sectionId) {
        var actorId = actor(authentication);
        var current = solutions.findById(solutionId).orElseThrow(AccessDeniedAsNotFoundException::new);
        access.requireView(actorId, current.spaceId(), clock.instant());
        var result = new ArrayList<VersionSummary>();
        for (var id : versions.listIds(solutionId, sectionId, 30)) {
            try {
                result.add(VersionSummary.from(readAuthorized(actorId, solutionId, sectionId, id)));
            } catch (AccessDeniedAsNotFoundException revoked) {
                // History metadata follows the same source ACL as the body.
            }
        }
        return List.copyOf(result);
    }

    @GetMapping("/api/v1/solutions/{solutionId}/sections/{sectionId}/versions/{versionId}")
    public VersionView version(Authentication authentication, @PathVariable UUID solutionId,
            @PathVariable UUID sectionId, @PathVariable UUID versionId) {
        return VersionView.from(readAuthorized(actor(authentication), solutionId, sectionId, versionId));
    }

    @GetMapping("/api/v1/solutions/{solutionId}/sections/{sectionId}/edits/latest")
    public LatestEditView latestEdit(Authentication authentication, @PathVariable UUID solutionId,
            @PathVariable UUID sectionId, @RequestParam UUID baselineVersionId) {
        var actorId = actor(authentication);
        readAuthorized(actorId, solutionId, sectionId, baselineVersionId);
        var latest = versions.latestEdit(solutionId, sectionId, baselineVersionId)
                .map(value -> VersionView.from(readAuthorized(actorId, solutionId, sectionId, value.id())))
                .orElse(null);
        return new LatestEditView(latest);
    }

    private SectionVersion readAuthorized(UUID actorId, UUID solutionId, UUID sectionId, UUID versionId) {
        var solution = solutions.findById(solutionId).orElseThrow(AccessDeniedAsNotFoundException::new);
        var at = clock.instant();
        access.requireView(actorId, solution.spaceId(), at);
        var section = sections.findById(versionId).orElseThrow(AccessDeniedAsNotFoundException::new);
        if (!section.solutionId().equals(solutionId) || !section.sectionId().equals(sectionId)) {
            throw new AccessDeniedAsNotFoundException();
        }
        if (section.kind() == SectionVersionKind.MODEL_DRAFT) throw new AccessDeniedAsNotFoundException();
        var requirement = requirements.findById(section.requirementVersionId())
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        sources.requireCurrentSources(actorId, solution.spaceId(), requirement, at);
        var provenance = evidenceSources.findBySectionVersionId(section.id());
        var sourceIds = new HashSet<UUID>();
        provenance.forEach(item -> sourceIds.add(item.evidenceId()));
        if (sourceIds.isEmpty() || !sourceIds.containsAll(section.evidenceIds())) {
            throw new AccessDeniedAsNotFoundException();
        }
        Set<UUID> allowed;
        try {
            allowed = evidenceAccess.authorize(actorId, solution.spaceId(), section.evidenceIds(), at);
        } catch (RuntimeException unavailable) {
            throw new SolutionAuthorityUnavailableException();
        }
        if (allowed == null || !allowed.equals(section.evidenceIds())) {
            throw new AccessDeniedAsNotFoundException();
        }
        return section;
    }

    private static UUID actor(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AuthenticationCredentialsNotFoundException("login required");
        }
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException invalid) {
            throw new AuthenticationCredentialsNotFoundException("invalid principal");
        }
    }

    public record EditRequest(UUID baselineVersionId, UUID expectedDraftVersionId,
            Long expectedRevision, String content) { }
    public record LatestEditView(VersionView version) { }
    public record VersionSummary(UUID id, String kind, UUID parentVersionId,
            UUID baselineVersionId, UUID createdBy, Instant createdAt) {
        static VersionSummary from(SectionVersion value) {
            return new VersionSummary(value.id(), value.kind().name(),
                    value.parentVersionId().orElse(null), value.baselineSectionVersionId().orElse(null),
                    value.createdBy().orElse(null), value.createdAt());
        }
    }
    public record VersionView(UUID id, String kind, String content, UUID parentVersionId,
            UUID baselineVersionId, UUID createdBy, Instant createdAt,
            List<com.yunjihai.rag.solution.domain.Claim> claims,
            com.yunjihai.rag.solution.verification.SolutionRiskReport verification) {
        static VersionView from(SectionVersion value) {
            return new VersionView(value.id(), value.kind().name(), value.content(),
                    value.parentVersionId().orElse(null), value.baselineSectionVersionId().orElse(null),
                    value.createdBy().orElse(null), value.createdAt(), value.claims(), value.verification());
        }
    }
}
