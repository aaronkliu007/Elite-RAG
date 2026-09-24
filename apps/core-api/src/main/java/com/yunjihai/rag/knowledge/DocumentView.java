package com.yunjihai.rag.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    public static DocumentView from(DocumentAggregate document) {
        return new DocumentView(
                document.id(),
                document.spaceId(),
                document.title(),
                document.versions().stream().map(VersionView::from).toList(),
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
