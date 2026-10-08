package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge-spaces")
@ConditionalOnBean({KnowledgeSpaceService.class, DocumentService.class})
public class KnowledgeController {

    private final KnowledgeSpaceService spaces;
    private final DocumentService documents;

    public KnowledgeController(KnowledgeSpaceService spaces, DocumentService documents) {
        this.spaces = spaces;
        this.documents = documents;
    }

    @PostMapping
    public KnowledgeSpace createSpace(Authentication authentication, @RequestBody CreateSpaceRequest request) {
        return spaces.createSpace(request.name(), userId(authentication), Instant.now());
    }

    @GetMapping("/{spaceId}/documents")
    public List<DocumentView> listDocuments(Authentication authentication, @PathVariable UUID spaceId) {
        return documents.listAuthorizedDocuments(userId(authentication), spaceId, Instant.now()).stream()
                .map(DocumentView::published)
                .toList();
    }

    @GetMapping("/documents/{documentId}")
    public DocumentView getDocument(Authentication authentication, @PathVariable UUID documentId) {
        return getDocument(userId(authentication), documentId, Instant.now());
    }

    public DocumentView getDocument(UUID userId, UUID documentId, Instant at) {
        return DocumentView.published(documents.getAuthorizedDocument(userId, documentId, at));
    }

    @GetMapping("/documents/{documentId}/parsed-content")
    public ResponseEntity<byte[]> getParsedContent(Authentication authentication, @PathVariable UUID documentId) {
        return response(getParsedContent(userId(authentication), documentId, Instant.now()), false);
    }

    public DocumentContent getParsedContent(UUID userId, UUID documentId, Instant at) {
        return documents.getAuthorizedParsedContent(userId, documentId, at);
    }

    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<byte[]> downloadSource(Authentication authentication, @PathVariable UUID documentId) {
        return response(downloadSource(userId(authentication), documentId, Instant.now()), true);
    }

    public DocumentContent downloadSource(UUID userId, UUID documentId, Instant at) {
        return documents.getAuthorizedSource(userId, documentId, at);
    }

    @PostMapping("/documents/{documentId}/versions/{versionId}/submit-review")
    public DocumentView submitForReview(
            Authentication authentication, @PathVariable UUID documentId, @PathVariable UUID versionId) {
        return DocumentView.management(documents.submitForReview(
                documentId, versionId, userId(authentication), Instant.now()));
    }

    @PostMapping("/documents/{documentId}/versions/{versionId}/publish")
    public DocumentView publish(
            Authentication authentication, @PathVariable UUID documentId, @PathVariable UUID versionId) {
        return DocumentView.management(documents.publish(
                documentId, versionId, userId(authentication), Instant.now()));
    }

    @PostMapping("/documents/{documentId}/archive")
    public DocumentView archive(Authentication authentication, @PathVariable UUID documentId) {
        return DocumentView.management(documents.archive(documentId, userId(authentication), Instant.now()));
    }

    private static ResponseEntity<byte[]> response(DocumentContent content, boolean attachment) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(content.mediaType()));
        if (attachment) {
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(content.filename(), StandardCharsets.UTF_8)
                    .build());
        }
        return ResponseEntity.ok().headers(headers).body(content.bytes());
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AccessDeniedAsNotFoundException();
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedAsNotFoundException();
        }
    }

    public record CreateSpaceRequest(String name) {
    }

}
