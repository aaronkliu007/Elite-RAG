package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.solution.domain.SolutionLifecycleException;
import com.yunjihai.rag.solution.proposal.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Returns only the exact snapshotted excerpt after current authorization checks. */
public final class DocumentRequirementEvidencePreviewService implements DocumentRequirementEvidencePreviewPort {
    private final DocumentRequirementProposalPort proposals;
    private final ProposalEvidenceStore snapshots;
    private final DocumentEvidenceContentPort content;
    public DocumentRequirementEvidencePreviewService(DocumentRequirementProposalPort proposals,
            ProposalEvidenceStore snapshots, DocumentEvidenceContentPort content) {
        this.proposals = Objects.requireNonNull(proposals); this.snapshots = Objects.requireNonNull(snapshots);
        this.content = Objects.requireNonNull(content);
    }
    public Preview preview(DocumentRequirementProposalService.Command command, UUID evidenceId) {
        Objects.requireNonNull(evidenceId);
        var attempt = proposals.find(command);
        if (attempt.status() != ProposalStatus.SUCCEEDED) throw new AccessDeniedAsNotFoundException();
        var snapshot = snapshots.find(attempt.id()).orElseThrow(AccessDeniedAsNotFoundException::new);
        var source = snapshot.sources().stream().filter(value -> value.evidenceId().equals(evidenceId))
                .findFirst().orElseThrow(AccessDeniedAsNotFoundException::new);
        final String text;
        try {
            text = content.readExcerpt(snapshot.spaceId(), source.document().documentId(),
                    source.document().versionId(), source.nodeId()).orElseThrow(AccessDeniedAsNotFoundException::new);
        } catch (AccessDeniedAsNotFoundException denied) { throw denied; }
        catch (RuntimeException unavailable) { throw new SolutionLifecycleException("DOCUMENT_EVIDENCE_UNAVAILABLE"); }
        if (!hash(text).equals(source.excerptHash())) throw new AccessDeniedAsNotFoundException();
        var current = proposals.find(command);
        if (!current.id().equals(attempt.id()) || current.status() != ProposalStatus.SUCCEEDED)
            throw new AccessDeniedAsNotFoundException();
        return new Preview(text);
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public record Preview(String text) {
        public Preview { Objects.requireNonNull(text); }
        @Override public String toString() { return "Preview[contentPresent=true]"; }
    }
}
