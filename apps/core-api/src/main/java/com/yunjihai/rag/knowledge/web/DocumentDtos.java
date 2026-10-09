package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.knowledge.DocumentParseErrorQuery;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.DocumentView;
import com.yunjihai.rag.knowledge.ParseStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class DocumentDtos {
    private DocumentDtos() { }

    public record DocumentDetail(
            UUID id,
            UUID spaceId,
            String title,
            MetadataView metadata,
            List<VersionView> versions,
            Optional<UUID> currentPublishedVersionId,
            boolean archived,
            List<ReviewView> reviews,
            Set<String> allowedActions) {
        public DocumentDetail {
            versions = List.copyOf(versions);
            reviews = List.copyOf(reviews);
            allowedActions = Set.copyOf(allowedActions);
        }

        public static DocumentDetail from(DocumentView view, DocumentParseErrorQuery parseErrors) {
            return new DocumentDetail(
                    view.id(), view.spaceId(), view.title(),
                    new MetadataView(view.metadata().classification(), view.metadata().tags()),
                    view.versions().stream()
                            .map(version -> VersionView.from(version, view.management(), parseErrors))
                            .toList(),
                    view.currentPublishedVersionId(), view.archived(),
                    view.reviews().stream().map(ReviewView::from).toList(),
                    view.allowedActions());
        }
    }

    public record MetadataView(String classification, List<String> tags) {
        public MetadataView { tags = List.copyOf(tags); }
    }

    public record VersionView(
            UUID id,
            int versionNumber,
            DocumentStatus status,
            ParseStatus parseStatus,
            Instant createdAt,
            Optional<String> parseErrorCode) {
        static VersionView from(
                DocumentView.VersionView version,
                boolean management,
                DocumentParseErrorQuery parseErrors) {
            return new VersionView(
                    version.id(), version.versionNumber(), version.status(), version.parseStatus(),
                    version.createdAt(), management ? parseErrors.findByVersionId(version.id()) : Optional.empty());
        }
    }

    public record ReviewView(
            UUID id,
            UUID versionId,
            UUID reviewerId,
            String decision,
            String comment,
            Instant reviewedAt) {
        static ReviewView from(DocumentView.ReviewView review) {
            return new ReviewView(
                    review.id(), review.versionId(), review.reviewerId(), review.decision().name(),
                    review.comment(), review.reviewedAt());
        }
    }

    public record UpdateMetadataRequest(String classification, List<String> tags) { }

    public record RejectRequest(String comment) { }

    public record UploadAccepted(UUID documentId, UUID versionId, String status) { }
}
