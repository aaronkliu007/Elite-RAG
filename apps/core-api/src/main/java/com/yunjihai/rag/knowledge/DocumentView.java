package com.yunjihai.rag.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;

public record DocumentView(
        UUID id,
        UUID spaceId,
        String title,
        List<VersionView> versions,
        Optional<UUID> currentPublishedVersionId,
        boolean archived) {

    public DocumentView {
        versions = List.copyOf(versions);
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
        return view(document, published);
    }

    public static DocumentView management(DocumentAggregate document) {
        return view(document, document.versions().stream().map(VersionView::from).toList());
    }

    private static DocumentView view(DocumentAggregate document, List<VersionView> versions) {
        return new DocumentView(
                document.id(),
                document.spaceId(),
                document.title(),
                versions,
                document.currentPublishedVersionId(),
                document.archived());
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
}
