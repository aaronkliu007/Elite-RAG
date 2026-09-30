package com.yunjihai.rag.retrieval.projection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RetrievalProjectionSnapshot(
        UUID spaceId,
        UUID documentId,
        UUID versionId,
        String title,
        long documentRevision,
        long projectionSequence,
        Instant publishedAt,
        Instant canonicalParsedAt,
        Set<ProjectionAclGrant> spaceAclGrants,
        Set<ProjectionAclGrant> documentAclGrants,
        boolean documentRestricted,
        Map<String, String> metadata,
        List<ProjectionNode> nodes,
        boolean indexable) {

    private static final Set<String> METADATA_KEYS = Set.of(
            "industry", "product", "solutionType", "year");

    public RetrievalProjectionSnapshot {
        Objects.requireNonNull(spaceId);
        Objects.requireNonNull(documentId);
        spaceAclGrants = Set.copyOf(Objects.requireNonNull(spaceAclGrants));
        documentAclGrants = Set.copyOf(Objects.requireNonNull(documentAclGrants));
        metadata = Map.copyOf(Objects.requireNonNull(metadata));
        nodes = List.copyOf(Objects.requireNonNull(nodes));
        if (documentRevision < 0 || projectionSequence < 0
                || !METADATA_KEYS.containsAll(metadata.keySet())) {
            throw new IllegalArgumentException("投影快照版本或元数据无效");
        }
        if (indexable && (versionId == null || title == null || title.isBlank()
                || publishedAt == null || canonicalParsedAt == null || nodes.isEmpty())) {
            throw new IllegalArgumentException("可索引投影快照不完整");
        }
        if (!indexable && (!nodes.isEmpty() || versionId != null || title != null
                || publishedAt != null || canonicalParsedAt != null)) {
            throw new IllegalArgumentException("删除投影快照不能携带可检索内容");
        }
    }

    public boolean deleteProjection() {
        return !indexable;
    }

    public static RetrievalProjectionSnapshot deletion(
            UUID spaceId, UUID documentId, long documentRevision, long projectionSequence) {
        return new RetrievalProjectionSnapshot(
                spaceId, documentId, null, null, documentRevision, projectionSequence,
                null, null, Set.of(), Set.of(), false, Map.of(), List.of(), false);
    }
}
