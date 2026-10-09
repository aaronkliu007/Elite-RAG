package com.yunjihai.rag.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;

public record DocumentView(
        UUID id,
        UUID spaceId,
        String title,
        DocumentMetadata metadata,
        List<VersionView> versions,
        Optional<UUID> currentPublishedVersionId,
        boolean archived,
        boolean management,
        List<ReviewView> reviews,
        Set<String> allowedActions) {

    public DocumentView {
        versions = List.copyOf(versions);
        reviews = List.copyOf(reviews);
        allowedActions = Set.copyOf(allowedActions);
    }

    public static DocumentView published(DocumentAggregate document) {
        if (document.archived() || document.currentPublishedVersionId().isEmpty()) {
            throw new AccessDeniedAsNotFoundException();
        }
        var published = document.currentPublishedVersionId()
                .map(document::version)
                .filter(version -> version.status() == DocumentStatus.PUBLISHED)
                .stream()
                .map(VersionView::from)
                .toList();
        if (published.isEmpty()) {
            throw new AccessDeniedAsNotFoundException();
        }
        return view(document, published, false, List.of(), Set.of("VIEW", "PREVIEW", "DOWNLOAD"));
    }

    public static DocumentView management(DocumentAggregate document) {
        return management(document, List.of(), Set.of(), false);
    }

    public static DocumentView management(
            DocumentAggregate document,
            List<DocumentReview> reviews,
            Set<KnowledgeWriteAuthorization.Action> authorizedActions,
            boolean readable) {
        var actions = new java.util.LinkedHashSet<String>();
        actions.add("VIEW");
        if (readable && document.currentPublishedVersionId().isPresent() && !document.archived()) {
            actions.add("PREVIEW");
            actions.add("DOWNLOAD");
        }
        var latest = document.latestVersion();
        if (authorizedActions.contains(KnowledgeWriteAuthorization.Action.EDIT_DOCUMENT)
                && latest.status() == DocumentStatus.DRAFT && !document.archived()) {
            actions.add("EDIT_METADATA");
        }
        if (authorizedActions.contains(KnowledgeWriteAuthorization.Action.SUBMIT_FOR_REVIEW)
                && latest.status() == DocumentStatus.DRAFT
                && latest.parseStatus() == ParseStatus.SUCCEEDED
                && document.metadata().complete() && !document.archived()) {
            actions.add("SUBMIT_REVIEW");
        }
        if (authorizedActions.contains(KnowledgeWriteAuthorization.Action.REVIEW_DOCUMENT)
                && latest.status() == DocumentStatus.IN_REVIEW && !document.archived()) {
            actions.add("REJECT");
        }
        if (authorizedActions.contains(KnowledgeWriteAuthorization.Action.PUBLISH_DOCUMENT)
                && latest.status() == DocumentStatus.IN_REVIEW
                && latest.parseStatus() == ParseStatus.SUCCEEDED && !document.archived()) {
            actions.add("PUBLISH");
        }
        if (authorizedActions.contains(KnowledgeWriteAuthorization.Action.ARCHIVE_DOCUMENT)
                && document.currentPublishedVersionId().isPresent() && !document.archived()) {
            actions.add("ARCHIVE");
        }
        return view(
                document,
                document.versions().stream().map(VersionView::from).toList(),
                true,
                reviews.stream().map(ReviewView::from).toList(),
                actions);
    }

    private static DocumentView view(
            DocumentAggregate document,
            List<VersionView> versions,
            boolean management,
            List<ReviewView> reviews,
            Set<String> allowedActions) {
        return new DocumentView(
                document.id(),
                document.spaceId(),
                document.title(),
                document.metadata(),
                versions,
                document.currentPublishedVersionId(),
                document.archived(),
                management,
                reviews,
                allowedActions);
    }

    public record VersionView(
            UUID id,
            int versionNumber,
            DocumentStatus status,
            ParseStatus parseStatus,
            Instant createdAt) {

        static VersionView from(DocumentVersion version) {
            return new VersionView(
                    version.id(), version.versionNumber(), version.status(),
                    version.parseStatus(), version.createdAt());
        }
    }

    public record ReviewView(
            UUID id,
            UUID versionId,
            UUID reviewerId,
            ReviewDecision decision,
            String comment,
            Instant reviewedAt) {
        static ReviewView from(DocumentReview review) {
            return new ReviewView(
                    review.id(), review.versionId(), review.reviewerId(), review.decision(),
                    review.comment(), review.reviewedAt());
        }
    }
}
