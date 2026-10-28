package com.yunjihai.rag.solution.finalization;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.solution.application.SolutionAccessService;
import com.yunjihai.rag.solution.application.SolutionSourceAccess;
import com.yunjihai.rag.solution.domain.SectionVersion;
import com.yunjihai.rag.solution.domain.SectionVersionKind;
import com.yunjihai.rag.solution.domain.RequirementSource;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.SlideLocator;
import com.yunjihai.rag.retrieval.domain.TableLocator;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.solution.generation.SectionVersionRepository;
import com.yunjihai.rag.solution.generation.GenerationEvidenceStore;
import com.yunjihai.rag.solution.generation.GenerationEvidenceSource;
import com.yunjihai.rag.solution.port.OutlineVersionRepository;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import com.yunjihai.rag.solution.port.SolutionRepository;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reauthorizes every source immediately before rendering an immutable finalized version. */
public final class SolutionWordExportService {
    private final SolutionRepository solutions;
    private final FinalizedSolutionVersionRepository finals;
    private final RequirementVersionRepository requirements;
    private final OutlineVersionRepository outlines;
    private final SectionVersionRepository sections;
    private final GenerationEvidenceStore evidenceSources;
    private final SolutionAccessService access;
    private final SolutionSourceAccess sources;
    private final EvidenceAccessPort evidenceAccess;
    private final SolutionWordRenderer renderer;
    private final AuditSink audit;
    private final Clock clock;

    public SolutionWordExportService(SolutionRepository solutions, FinalizedSolutionVersionRepository finals,
            RequirementVersionRepository requirements, OutlineVersionRepository outlines,
            SectionVersionRepository sections, GenerationEvidenceStore evidenceSources,
            SolutionAccessService access, SolutionSourceAccess sources,
            EvidenceAccessPort evidenceAccess, SolutionWordRenderer renderer, AuditSink audit, Clock clock) {
        this.solutions = Objects.requireNonNull(solutions);
        this.finals = Objects.requireNonNull(finals);
        this.requirements = Objects.requireNonNull(requirements);
        this.outlines = Objects.requireNonNull(outlines);
        this.sections = Objects.requireNonNull(sections);
        this.evidenceSources = Objects.requireNonNull(evidenceSources);
        this.access = Objects.requireNonNull(access);
        this.sources = Objects.requireNonNull(sources);
        this.evidenceAccess = Objects.requireNonNull(evidenceAccess);
        this.renderer = Objects.requireNonNull(renderer);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public byte[] export(UUID actorId, UUID solutionId, UUID versionId) {
        try {
            var result = render(actorId, solutionId, versionId);
            audit.record(new AuditCommand(actorId, AuditAction.DOWNLOAD, "SOLUTION", solutionId,
                    AuditOutcome.SUCCEEDED, Map.of("solutionId", solutionId.toString(),
                            "finalVersionId", versionId.toString(), "status", "SUCCEEDED")));
            return result;
        } catch (RuntimeException failure) {
            try {
                audit.record(new AuditCommand(actorId, AuditAction.DOWNLOAD, "SOLUTION", solutionId,
                        failure instanceof AccessDeniedAsNotFoundException ? AuditOutcome.DENIED : AuditOutcome.FAILED,
                        Map.of("solutionId", solutionId.toString(),
                                "finalVersionId", versionId.toString(), "status", "FAILED")));
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }

    private byte[] render(UUID actorId, UUID solutionId, UUID versionId) {
        Objects.requireNonNull(actorId);
        var solution = solutions.findById(solutionId).orElseThrow(AccessDeniedAsNotFoundException::new);
        var at = clock.instant();
        access.requireView(actorId, solution.spaceId(), at);
        var finalized = finals.findById(versionId).orElseThrow(AccessDeniedAsNotFoundException::new);
        if (!finalized.solutionId().equals(solutionId)) throw new AccessDeniedAsNotFoundException();
        var requirement = requirements.findById(finalized.requirementVersionId())
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        sources.requireCurrentSources(actorId, solution.spaceId(), requirement, at);
        var outline = outlines.findById(finalized.outlineVersionId())
                .orElseThrow(AccessDeniedAsNotFoundException::new);
        var chapters = new ArrayList<SolutionWordRenderer.Chapter>();
        var evidence = new HashSet<UUID>();
        var selected = new HashSet<UUID>(finalized.sectionVersionIds());
        if (selected.size() != finalized.sectionVersionIds().size()) throw new AccessDeniedAsNotFoundException();
        for (var node : outline.nodes()) {
            SectionVersion section = null;
            for (var sectionId : finalized.sectionVersionIds()) {
                var candidate = sections.findById(sectionId).orElseThrow(AccessDeniedAsNotFoundException::new);
                if (candidate.sectionId().equals(node.sectionId())) section = candidate;
            }
            if (section == null) {
                if (node.required()) throw new AccessDeniedAsNotFoundException();
                continue;
            }
            if (section.kind() != SectionVersionKind.ADOPTED
                    || !section.solutionId().equals(solutionId)
                    || !section.requirementVersionId().equals(finalized.requirementVersionId())
                    || !section.outlineVersionId().equals(finalized.outlineVersionId())) {
                throw new AccessDeniedAsNotFoundException();
            }
            evidence.addAll(section.evidenceIds());
            var provenance = provenance(section);
            var citations = section.evidenceIds().stream().sorted()
                    .map(id -> provenance.stream().filter(source -> source.evidenceId().equals(id))
                            .findFirst().orElseThrow(AccessDeniedAsNotFoundException::new))
                    .map(source -> "证据 " + source.evidenceId() + "｜文档 " + source.documentId()
                            + "｜版本 " + source.versionId() + "｜节点 " + source.nodeId())
                    .toList();
            chapters.add(new SolutionWordRenderer.Chapter(chapters.size() + 1, node.title(),
                    section.content(), citations));
        }
        if (chapters.size() != selected.size() || chapters.isEmpty()) throw new AccessDeniedAsNotFoundException();
        Set<UUID> allowed;
        try {
            allowed = evidenceAccess.authorize(actorId, solution.spaceId(), Set.copyOf(evidence), at);
        } catch (RuntimeException unavailable) {
            throw new AccessDeniedAsNotFoundException();
        }
        if (allowed == null || !allowed.equals(evidence)) throw new AccessDeniedAsNotFoundException();
        return renderer.render(new SolutionWordRenderer.Document(solution.name().value(),
                finalized.solutionRevision(), finalized.createdAt(), finalized.contentHash(),
                requirement.items().stream().map(item -> new SolutionWordRenderer.RequirementLine(
                        item.text(), item.sources().stream().map(SolutionWordExportService::sourceLine).toList()))
                        .toList(), List.copyOf(chapters)));
    }

    private static String sourceLine(RequirementSource source) {
        var ref = source.documentVersion();
        var locator = source.locator();
        String location;
        if (locator instanceof PageLocator page) {
            location = "页码 " + page.pageNumber()
                    + (page.boundingBox() == null ? "" : "｜区域 " + page.boundingBox());
        } else if (locator instanceof SlideLocator slide) {
            location = "幻灯片 " + slide.slideNumber();
        } else if (locator instanceof TableLocator table) {
            location = "表格 " + table.tableName() + "｜行 " + table.startRow() + "-" + table.endRow()
                    + "｜列 " + table.startColumn() + "-" + table.endColumn();
        } else if (locator instanceof MarkdownLocator markdown) {
            location = "标题 " + String.join(" / ", markdown.headingPath())
                    + (markdown.nodeId() == null ? "" : "｜节点 " + markdown.nodeId());
        } else {
            throw new AccessDeniedAsNotFoundException();
        }
        return "文档 " + ref.documentId() + "｜版本 " + ref.versionId() + "｜" + location;
    }

    private List<GenerationEvidenceSource> provenance(SectionVersion adopted) {
        var cursor = adopted;
        for (int i = 0; i < 16; i++) {
            if (cursor.kind() == SectionVersionKind.MODEL_DRAFT) {
                return evidenceSources.findBySectionVersionId(cursor.id());
            }
            cursor = sections.findById(cursor.parentVersionId()
                    .orElseThrow(AccessDeniedAsNotFoundException::new))
                    .orElseThrow(AccessDeniedAsNotFoundException::new);
            if (!cursor.solutionId().equals(adopted.solutionId())
                    || !cursor.sectionId().equals(adopted.sectionId())) {
                throw new AccessDeniedAsNotFoundException();
            }
        }
        throw new AccessDeniedAsNotFoundException();
    }
}
