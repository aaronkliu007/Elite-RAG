package com.yunjihai.rag.solution.generation;

import com.yunjihai.rag.retrieval.domain.Evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Minimal persisted identity for one retrieved evidence item; never stores its excerpt. */
public record GenerationEvidenceSource(int position, UUID evidenceId, UUID spaceId,
        UUID documentId, UUID versionId, String nodeId, String excerptHash) {
    public GenerationEvidenceSource {
        if (position < 1) throw new IllegalArgumentException("invalid evidence position");
        Objects.requireNonNull(evidenceId);
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(versionId);
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(excerptHash);
        if (nodeId.isBlank() || !excerptHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid evidence source");
        }
    }

    public static GenerationEvidenceSource from(int position, Evidence evidence) {
        return new GenerationEvidenceSource(position, evidence.evidenceId(), evidence.spaceId(),
                evidence.documentId(), evidence.versionId(), evidence.nodeId(), hash(evidence.excerpt()));
    }

    public boolean matches(Evidence evidence) {
        return spaceId.equals(evidence.spaceId())
                && documentId.equals(evidence.documentId())
                && versionId.equals(evidence.versionId())
                && nodeId.equals(evidence.nodeId())
                && excerptHash.equals(hash(evidence.excerpt()));
    }

    public Evidence withOriginalId(Evidence fresh) {
        if (!matches(fresh)) throw new IllegalArgumentException("evidence source changed");
        return new Evidence(fresh.spaceId(), evidenceId, fresh.documentId(), fresh.versionId(),
                fresh.nodeId(), fresh.title(), fresh.excerpt(), fresh.score(), fresh.locator());
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
