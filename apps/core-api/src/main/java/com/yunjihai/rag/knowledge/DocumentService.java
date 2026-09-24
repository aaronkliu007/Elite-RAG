package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

public class DocumentService {

    private final DocumentRepository repository;
    private final AccessDecisionService access;
    private final DocumentContentGateway content;
    private final AuthorizedDocumentQuery authorizedQuery;

    public DocumentService(
            DocumentRepository repository,
            AccessDecisionService access,
            DocumentContentGateway content,
            AuthorizedDocumentQuery authorizedQuery) {
        this.repository = Objects.requireNonNull(repository);
        this.access = Objects.requireNonNull(access);
        this.content = Objects.requireNonNull(content);
        this.authorizedQuery = Objects.requireNonNull(authorizedQuery);
    }

    @Transactional
    public DocumentAggregate createDraft(
            UUID spaceId, String title, String sourceBlobId, UUID actorId, Instant at) {
        var document = DocumentAggregate.create(spaceId, title, sourceBlobId, actorId, at);
        repository.insert(document);
        return document;
    }

    @Transactional
    public DocumentAggregate createNextDraft(UUID documentId, String sourceBlobId, UUID actorId, Instant at) {
        return mutate(documentId, document -> document.appendDraft(sourceBlobId, actorId, at));
    }

    @Transactional
    public DocumentAggregate submitForReview(UUID documentId, UUID versionId, UUID actorId, Instant at) {
        return mutate(documentId, document -> document.submitForReview(versionId, actorId, at));
    }

    @Transactional
    public DocumentAggregate markParsingSucceeded(UUID documentId, UUID versionId, Instant at) {
        return mutate(documentId, document -> document.markParsingSucceeded(versionId, at));
    }

    @Transactional
    public DocumentAggregate publish(UUID documentId, UUID versionId, UUID actorId, Instant at) {
        return mutate(documentId, document -> document.publish(versionId, actorId, at));
    }

    @Transactional
    public DocumentAggregate archive(UUID documentId, UUID actorId, Instant at) {
        return mutate(documentId, document -> document.archive(actorId, at));
    }

    @Transactional(readOnly = true)
    public DocumentAggregate getAuthorizedDocument(UUID userId, UUID documentId, Instant at) {
        var document = findOrHide(documentId);
        access.requireRead(userId, document.spaceId(), document.id(), at);
        if (document.archived() || document.currentPublishedVersionId().isEmpty()) {
            throw new AccessDeniedAsNotFoundException();
        }
        return document;
    }

    @Transactional(readOnly = true)
    public List<DocumentAggregate> listAuthorizedDocuments(UUID userId, UUID spaceId, Instant at) {
        return List.copyOf(authorizedQuery.listAuthorized(userId, spaceId, at));
    }

    @Transactional(readOnly = true)
    public DocumentContent getAuthorizedParsedContent(UUID userId, UUID documentId, Instant at) {
        var document = getAuthorizedDocument(userId, documentId, at);
        return content.readParsed(document.id(), document.currentPublishedVersionId().orElseThrow());
    }

    @Transactional(readOnly = true)
    public DocumentContent getAuthorizedSource(UUID userId, UUID documentId, Instant at) {
        var document = getAuthorizedDocument(userId, documentId, at);
        return content.readSource(document.id(), document.currentPublishedVersionId().orElseThrow());
    }

    private DocumentAggregate mutate(UUID documentId, UnaryOperator<DocumentAggregate> operation) {
        var current = findOrThrow(documentId);
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
}
