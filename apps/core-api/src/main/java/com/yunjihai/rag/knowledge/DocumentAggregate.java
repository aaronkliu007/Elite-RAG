package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.common.UuidV7;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DocumentAggregate(
        UUID id,
        UUID spaceId,
        String title,
        List<DocumentVersion> versions,
        UUID publishedVersionId,
        boolean archived,
        long revision) {

    public DocumentAggregate {
        Objects.requireNonNull(id);
        Objects.requireNonNull(spaceId);
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("文档标题不能为空");
        }
        versions = List.copyOf(versions);
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("文档必须至少包含一个版本");
        }
    }

    static DocumentAggregate create(
            UUID spaceId,
            String title,
            String sourceBlobId,
            UUID actorId,
            Instant at) {
        return new DocumentAggregate(
                UuidV7.generate(at), spaceId, title,
                List.of(DocumentVersion.draft(1, sourceBlobId, actorId, at)),
                null, false, 0);
    }

    public DocumentVersion latestVersion() {
        return versions.getLast();
    }

    public DocumentVersion version(UUID versionId) {
        return versions.stream()
                .filter(candidate -> candidate.id().equals(versionId))
                .findFirst()
                .orElseThrow(() -> new DocumentLifecycleException("文档版本不存在"));
    }

    public Optional<UUID> currentPublishedVersionId() {
        return Optional.ofNullable(publishedVersionId);
    }

    DocumentAggregate appendDraft(String sourceBlobId, UUID actorId, Instant at) {
        ensureNotArchived();
        var next = DocumentVersion.draft(latestVersion().versionNumber() + 1, sourceBlobId, actorId, at);
        var updated = new ArrayList<>(versions);
        updated.add(next);
        return copy(updated, publishedVersionId, false);
    }

    DocumentAggregate submitForReview(UUID versionId, UUID actorId, Instant at) {
        var current = mutableLatest(versionId);
        if (current.status() != DocumentStatus.DRAFT) {
            throw new DocumentLifecycleException("只有草稿可以提交评审");
        }
        if (current.parseStatus() != ParseStatus.SUCCEEDED) {
            throw new DocumentLifecycleException("文档解析成功后才能提交评审");
        }
        return replaceVersion(current.withStatus(DocumentStatus.IN_REVIEW, actorId, at), publishedVersionId, false);
    }

    DocumentAggregate markParsingSucceeded(UUID versionId, Instant at) {
        var current = mutableLatest(versionId);
        if (current.status() != DocumentStatus.DRAFT && current.status() != DocumentStatus.IN_REVIEW) {
            throw new DocumentLifecycleException("已发布或已归档版本不能修改解析状态");
        }
        return replaceVersion(current.withParseStatus(ParseStatus.SUCCEEDED, at), publishedVersionId, false);
    }

    DocumentAggregate completeParsing(UUID versionId, UUID actorId, Instant at) {
        var current = mutableLatest(versionId);
        if (current.status() != DocumentStatus.DRAFT) {
            throw new DocumentLifecycleException("只有草稿可以完成解析并进入评审");
        }
        var completed = current.withParseStatus(ParseStatus.SUCCEEDED, at)
                .withStatus(DocumentStatus.IN_REVIEW, actorId, at);
        return replaceVersion(completed, publishedVersionId, false);
    }

    DocumentAggregate markParsingFailed(UUID versionId, Instant at) {
        var current = mutableLatest(versionId);
        if (current.status() != DocumentStatus.DRAFT) {
            throw new DocumentLifecycleException("只有草稿可以记录解析失败");
        }
        return replaceVersion(current.withParseStatus(ParseStatus.FAILED, at), publishedVersionId, false);
    }

    DocumentAggregate publish(UUID versionId, UUID actorId, Instant at) {
        var current = mutableLatest(versionId);
        if (current.status() != DocumentStatus.IN_REVIEW) {
            throw new DocumentLifecycleException("只有评审中的版本可以发布");
        }
        if (current.parseStatus() != ParseStatus.SUCCEEDED) {
            throw new DocumentLifecycleException("文档解析成功后才能发布");
        }
        return replaceVersion(current.withStatus(DocumentStatus.PUBLISHED, actorId, at), versionId, false);
    }

    DocumentAggregate archive(UUID actorId, Instant at) {
        ensureNotArchived();
        if (publishedVersionId == null) {
            throw new DocumentLifecycleException("尚未发布的文档不能归档");
        }
        var current = version(publishedVersionId);
        return replaceVersion(current.withStatus(DocumentStatus.ARCHIVED, actorId, at), publishedVersionId, true);
    }

    private DocumentVersion mutableLatest(UUID versionId) {
        ensureNotArchived();
        if (!latestVersion().id().equals(versionId)) {
            throw new DocumentLifecycleException("只能操作最新文档版本");
        }
        return latestVersion();
    }

    private void ensureNotArchived() {
        if (archived) {
            throw new DocumentLifecycleException("已归档文档不可再修改");
        }
    }

    private DocumentAggregate replaceVersion(
            DocumentVersion replacement,
            UUID publishedVersionId,
            boolean nextArchived) {
        var updated = versions.stream()
                .map(current -> current.id().equals(replacement.id()) ? replacement : current)
                .toList();
        return copy(updated, publishedVersionId, nextArchived);
    }

    private DocumentAggregate copy(List<DocumentVersion> nextVersions, UUID publishedVersionId, boolean nextArchived) {
        return new DocumentAggregate(
                id, spaceId, title, nextVersions, publishedVersionId, nextArchived, revision + 1);
    }
}
