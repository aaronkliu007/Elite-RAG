package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.audit.AuditAction;
import com.yunjihai.rag.audit.AuditCommand;
import com.yunjihai.rag.audit.AuditOutcome;
import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

public class DocumentService {

    private final DocumentRepository repository;
    private final AccessDecisionService access;
    private final DocumentContentGateway content;
    private final AuthorizedDocumentQuery authorizedQuery;
    private final KnowledgeSpaceReadAuthorization spaceReadAuthorization;
    private final KnowledgeWriteAuthorization writeAuthorization;
    private final AuditSink audit;
    private final ProjectionChangePublisher projections;
    private final DocumentReviewStore reviews;

    public DocumentService(
            DocumentRepository repository,
            AccessDecisionService access,
            DocumentContentGateway content,
            AuthorizedDocumentQuery authorizedQuery,
            KnowledgeWriteAuthorization writeAuthorization) {
        this(repository, access, content, authorizedQuery,
                KnowledgeSpaceReadAuthorization.denyAll(), writeAuthorization,
                AuditSink.noop(), ProjectionChangePublisher.noop());
    }

    public DocumentService(
            DocumentRepository repository,
            AccessDecisionService access,
            DocumentContentGateway content,
            AuthorizedDocumentQuery authorizedQuery,
            KnowledgeWriteAuthorization writeAuthorization,
            DocumentReviewStore reviews) {
        this(repository, access, content, authorizedQuery,
                KnowledgeSpaceReadAuthorization.denyAll(), writeAuthorization,
                AuditSink.noop(), ProjectionChangePublisher.noop(), reviews);
    }

    public DocumentService(
            DocumentRepository repository,
            AccessDecisionService access,
            DocumentContentGateway content,
            AuthorizedDocumentQuery authorizedQuery,
            KnowledgeWriteAuthorization writeAuthorization,
            AuditSink audit) {
        this(repository, access, content, authorizedQuery,
                KnowledgeSpaceReadAuthorization.denyAll(), writeAuthorization,
                audit, ProjectionChangePublisher.noop());
    }

    public DocumentService(
            DocumentRepository repository,
            AccessDecisionService access,
            DocumentContentGateway content,
            AuthorizedDocumentQuery authorizedQuery,
            KnowledgeSpaceReadAuthorization spaceReadAuthorization,
            KnowledgeWriteAuthorization writeAuthorization) {
        this(repository, access, content, authorizedQuery,
                spaceReadAuthorization, writeAuthorization,
                AuditSink.noop(), ProjectionChangePublisher.noop());
    }

    public DocumentService(
            DocumentRepository repository,
            AccessDecisionService access,
            DocumentContentGateway content,
            AuthorizedDocumentQuery authorizedQuery,
            KnowledgeSpaceReadAuthorization spaceReadAuthorization,
            KnowledgeWriteAuthorization writeAuthorization,
            AuditSink audit) {
        this(repository, access, content, authorizedQuery, spaceReadAuthorization,
                writeAuthorization, audit, ProjectionChangePublisher.noop());
    }

    public DocumentService(
            DocumentRepository repository,
            AccessDecisionService access,
            DocumentContentGateway content,
            AuthorizedDocumentQuery authorizedQuery,
            KnowledgeSpaceReadAuthorization spaceReadAuthorization,
            KnowledgeWriteAuthorization writeAuthorization,
            AuditSink audit,
            ProjectionChangePublisher projections) {
        this(repository, access, content, authorizedQuery, spaceReadAuthorization,
                writeAuthorization, audit, projections, DocumentReviewStore.noop());
    }

    public DocumentService(
            DocumentRepository repository,
            AccessDecisionService access,
            DocumentContentGateway content,
            AuthorizedDocumentQuery authorizedQuery,
            KnowledgeSpaceReadAuthorization spaceReadAuthorization,
            KnowledgeWriteAuthorization writeAuthorization,
            AuditSink audit,
            ProjectionChangePublisher projections,
            DocumentReviewStore reviews) {
        this.repository = Objects.requireNonNull(repository);
        this.access = Objects.requireNonNull(access);
        this.content = Objects.requireNonNull(content);
        this.authorizedQuery = Objects.requireNonNull(authorizedQuery);
        this.spaceReadAuthorization = Objects.requireNonNull(spaceReadAuthorization);
        this.writeAuthorization = Objects.requireNonNull(writeAuthorization);
        this.audit = Objects.requireNonNull(audit);
        this.projections = Objects.requireNonNull(projections);
        this.reviews = Objects.requireNonNull(reviews);
    }

    @Transactional
    public DocumentAggregate createDraft(
            UUID spaceId, String title, String sourceBlobId, UUID actorId, Instant at) {
        writeAuthorization.require(actorId, spaceId, null, KnowledgeWriteAuthorization.Action.CREATE_DOCUMENT);
        var document = DocumentAggregate.create(spaceId, title, sourceBlobId, actorId, at);
        repository.insert(document);
        return document;
    }

    @Transactional
    public DocumentVersionRef createOrReuseDraft(
            UUID spaceId, String title, String sourceBlobId, UUID actorId, Instant at) {
        writeAuthorization.require(actorId, spaceId, null, KnowledgeWriteAuthorization.Action.CREATE_DOCUMENT);
        return repository.findActiveVersionBySpaceAndSourceBlob(spaceId, sourceBlobId)
                .orElseGet(() -> {
                    var document = DocumentAggregate.create(spaceId, title, sourceBlobId, actorId, at);
                    repository.insert(document);
                    return new DocumentVersionRef(document.id(), document.latestVersion().id());
                });
    }

    @Transactional
    public DocumentAggregate createNextDraft(UUID documentId, String sourceBlobId, UUID actorId, Instant at) {
        var updated = mutateAuthorized(documentId, actorId, KnowledgeWriteAuthorization.Action.EDIT_DOCUMENT,
                document -> document.appendDraft(sourceBlobId, actorId, at));
        record(actorId, AuditAction.EDIT, "DOCUMENT_VERSION", updated.latestVersion().id(), updated);
        return updated;
    }

    @Transactional
    public DocumentAggregate updateMetadata(
            UUID documentId, DocumentMetadata metadata, UUID actorId, Instant at) {
        var updated = mutateAuthorized(documentId, actorId, KnowledgeWriteAuthorization.Action.EDIT_DOCUMENT,
                document -> document.updateMetadata(metadata));
        record(actorId, AuditAction.EDIT, "DOCUMENT", documentId, updated);
        return updated;
    }

    @Transactional
    public DocumentAggregate submitForReview(UUID documentId, UUID versionId, UUID actorId, Instant at) {
        var updated = mutateAuthorized(documentId, actorId, KnowledgeWriteAuthorization.Action.SUBMIT_FOR_REVIEW,
                document -> document.submitForReview(versionId, actorId, at));
        record(actorId, AuditAction.REVIEW, "DOCUMENT_VERSION", versionId, updated);
        return updated;
    }

    @Transactional
    public DocumentAggregate markParsingSucceeded(UUID documentId, UUID versionId, Instant at) {
        return mutate(documentId, document -> document.markParsingSucceeded(versionId, at));
    }

    @Transactional
    public DocumentAggregate completeParsing(
            UUID documentId, UUID versionId, UUID actorId, Instant at) {
        return completeParsingWithContent(documentId, versionId, actorId, at, () -> { });
    }

    @Transactional
    public DocumentAggregate completeParsingWithContent(
            UUID documentId,
            UUID versionId,
            UUID actorId,
            Instant at,
            Runnable persistParsedContent) {
        Objects.requireNonNull(persistParsedContent);
        return projections.withDocumentChangeLock(documentId, () -> {
            var current = findOrThrow(documentId);
            var version = current.version(versionId);
            if (current.archived() || !current.latestVersion().id().equals(versionId)
                    || version.status() == DocumentStatus.PUBLISHED
                    || version.status() == DocumentStatus.ARCHIVED
                    || version.parseStatus() == ParseStatus.SUCCEEDED) {
                return current;
            }
            var updated = current.completeParsing(versionId, actorId, at);
            repository.replace(updated, current.revision());
            persistParsedContent.run();
            projections.documentChanged(updated, versionId, at);
            return updated;
        });
    }

    @Transactional
    public DocumentAggregate markParsingFailed(UUID documentId, UUID versionId, Instant at) {
        return mutate(documentId, document -> document.markParsingFailed(versionId, at));
    }

    @Transactional
    public DocumentAggregate markParsingFailedForIngestion(UUID documentId, UUID versionId, Instant at) {
        var current = findOrThrow(documentId);
        // 必须先确认版本归属；不存在的版本仍属于不一致，不能伪装成过期任务。
        var version = current.version(versionId);
        if (current.archived() || !current.latestVersion().id().equals(versionId)
                || version.status() == DocumentStatus.PUBLISHED || version.status() == DocumentStatus.ARCHIVED) {
            // 旧任务可以终结，但不能改写新版本、已发布结果或归档历史。
            return current;
        }
        var updated = current.markParsingFailed(versionId, at);
        repository.replace(updated, current.revision());
        return updated;
    }

    @Transactional
    public DocumentAggregate publish(UUID documentId, UUID versionId, UUID actorId, Instant at) {
        return projections.withDocumentChangeLock(documentId, () -> {
            var updated = mutateAuthorized(documentId, actorId, KnowledgeWriteAuthorization.Action.PUBLISH_DOCUMENT,
                    document -> document.publish(versionId, actorId, at));
            reviews.append(DocumentReview.published(documentId, versionId, actorId, at));
            record(actorId, AuditAction.PUBLISH, "DOCUMENT_VERSION", versionId, updated);
            projections.documentChanged(updated, versionId, at);
            return updated;
        });
    }

    @Transactional
    public DocumentAggregate reject(
            UUID documentId, UUID versionId, UUID reviewerId, String comment, Instant at) {
        var review = DocumentReview.rejected(documentId, versionId, reviewerId, comment, at);
        var updated = mutateAuthorized(documentId, reviewerId, KnowledgeWriteAuthorization.Action.REVIEW_DOCUMENT,
                document -> document.reject(versionId, reviewerId, at));
        reviews.append(review);
        record(reviewerId, AuditAction.REVIEW, "DOCUMENT_VERSION", versionId, updated);
        return updated;
    }

    @Transactional
    public DocumentAggregate archive(UUID documentId, UUID actorId, Instant at) {
        return projections.withDocumentChangeLock(documentId, () -> {
            var updated = mutateAuthorized(documentId, actorId, KnowledgeWriteAuthorization.Action.ARCHIVE_DOCUMENT,
                    document -> document.archive(actorId, at));
            record(actorId, AuditAction.ARCHIVE, "DOCUMENT", documentId, updated);
            projections.documentChanged(updated, updated.currentPublishedVersionId().orElseThrow(), at);
            return updated;
        });
    }

    @Transactional
    public DocumentAggregate getAuthorizedDocument(UUID userId, UUID documentId, Instant at) {
        var document = findOrHide(documentId);
        access.requireRead(userId, document.spaceId(), document.id(), at);
        if (document.archived() || document.currentPublishedVersionId().isEmpty()
                || document.currentPublishedVersionId()
                .map(document::version)
                .map(version -> version.status() != DocumentStatus.PUBLISHED)
                .orElse(true)) {
            throw new AccessDeniedAsNotFoundException();
        }
        record(userId, AuditAction.VIEW, "DOCUMENT", documentId, document);
        return document;
    }

    @Transactional(readOnly = true)
    public List<DocumentAggregate> listAuthorizedDocuments(UUID userId, UUID spaceId, Instant at) {
        spaceReadAuthorization.requireRead(userId, spaceId, at);
        return authorizedQuery.listAuthorized(userId, spaceId, at).stream()
                .filter(document -> document.spaceId().equals(spaceId))
                .filter(document -> !document.archived())
                .filter(document -> document.currentPublishedVersionId().isPresent())
                .filter(document -> document.currentPublishedVersionId()
                        .map(document::version)
                        .map(version -> version.status() == DocumentStatus.PUBLISHED)
                        .orElse(false))
                .filter(document -> access.canRead(userId, spaceId, document.id(), at))
                .toList();
    }

    @Transactional
    public DocumentContent getAuthorizedParsedContent(UUID userId, UUID documentId, Instant at) {
        var document = getAuthorizedDocument(userId, documentId, at);
        return content.readParsed(document.id(), document.currentPublishedVersionId().orElseThrow());
    }

    @Transactional
    public DocumentContent getAuthorizedSource(UUID userId, UUID documentId, Instant at) {
        var document = getAuthorizedDocument(userId, documentId, at);
        var source = content.readSource(document.id(), document.currentPublishedVersionId().orElseThrow());
        record(userId, AuditAction.DOWNLOAD, "DOCUMENT", documentId, document);
        return source;
    }

    private DocumentAggregate mutate(UUID documentId, UnaryOperator<DocumentAggregate> operation) {
        var current = findOrThrow(documentId);
        var updated = operation.apply(current);
        repository.replace(updated, current.revision());
        return updated;
    }

    private DocumentAggregate mutateAuthorized(
            UUID documentId,
            UUID actorId,
            KnowledgeWriteAuthorization.Action action,
            UnaryOperator<DocumentAggregate> operation) {
        var current = findOrHide(documentId);
        writeAuthorization.require(actorId, current.spaceId(), current.id(), action);
        var updated = operation.apply(current);
        repository.replace(updated, current.revision());
        return updated;
    }

    private DocumentAggregate findOrThrow(UUID documentId) {
        return repository.findById(documentId)
                .orElseThrow(() -> new KnowledgeNotFoundException("文档不存在"));
    }

    private DocumentAggregate findOrHide(UUID documentId) {
        return repository.findById(documentId)
                .orElseThrow(AccessDeniedAsNotFoundException::new);
    }

    private void record(
            UUID actorId,
            AuditAction action,
            String resourceType,
            UUID resourceId,
            DocumentAggregate document) {
        audit.record(new AuditCommand(
                actorId,
                action,
                resourceType,
                resourceId,
                AuditOutcome.SUCCEEDED,
                Map.of("spaceId", document.spaceId().toString())));
    }
}
