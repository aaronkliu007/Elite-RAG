package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.api.idempotency.IdempotencyScope;
import com.yunjihai.rag.api.idempotency.IdempotencyService;
import com.yunjihai.rag.knowledge.DocumentContent;
import com.yunjihai.rag.knowledge.DocumentMetadata;
import com.yunjihai.rag.knowledge.DocumentParseErrorQuery;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.DocumentAggregate;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@ConditionalOnBean({
        DocumentService.class,
        IdempotencyService.class,
        DocumentParseErrorQuery.class
})
public class DocumentController {
    private final DocumentService documents;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;
    private final DocumentParseErrorQuery parseErrors;
    private final Clock clock;

    public DocumentController(
            DocumentService documents,
            IdempotencyService idempotency,
            ObjectMapper json,
            DocumentParseErrorQuery parseErrors,
            Clock clock) {
        this.documents = Objects.requireNonNull(documents);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.json = Objects.requireNonNull(json);
        this.parseErrors = Objects.requireNonNull(parseErrors);
        this.clock = Objects.requireNonNull(clock);
    }

    @GetMapping("/api/v1/knowledge-spaces/{spaceId}/documents")
    public List<DocumentDtos.DocumentDetail> list(
            Authentication authentication,
            @PathVariable UUID spaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("分页参数不正确");
        }
        UUID actorId = userId(authentication);
        long offset = (long) (page - 1) * pageSize;
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("分页参数不正确");
        }
        return documents.listVisibleDocuments(
                        actorId, spaceId, clock.instant(), (int) offset, pageSize).stream()
                .map(this::detail)
                .toList();
    }

    List<DocumentDtos.DocumentDetail> list(Authentication authentication, UUID spaceId) {
        return list(authentication, spaceId, 1, 20);
    }

    @GetMapping("/api/v1/documents/{documentId}")
    public DocumentDtos.DocumentDetail get(
            Authentication authentication,
            @PathVariable UUID documentId) {
        return detail(userId(authentication), documentId);
    }

    @GetMapping("/api/v1/documents/{documentId}/preview")
    public ResponseEntity<byte[]> preview(
            Authentication authentication,
            @PathVariable UUID documentId) {
        return contentResponse(
                documents.getAuthorizedParsedContent(userId(authentication), documentId, clock.instant()), false);
    }

    @GetMapping("/api/v1/documents/{documentId}/download")
    public ResponseEntity<byte[]> download(
            Authentication authentication,
            @PathVariable UUID documentId) {
        return contentResponse(
                documents.getAuthorizedSource(userId(authentication), documentId, clock.instant()), true);
    }

    @PatchMapping("/api/v1/documents/{documentId}")
    public DocumentDtos.DocumentDetail updateMetadata(
            Authentication authentication,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DocumentDtos.UpdateMetadataRequest request) {
        UUID actorId = userId(authentication);
        var validated = requireRequest(request);
        var metadata = new DocumentMetadata(validated.classification(), requireTags(validated.tags()));
        return execute(
                actorId, KnowledgeWriteAuthorization.Action.EDIT_DOCUMENT,
                "document.metadata.update", idempotencyKey,
                new MutationInput(documentId, null, validated),
                () -> documents.updateMetadata(documentId, metadata, actorId, clock.instant()));
    }

    @PostMapping("/api/v1/documents/{documentId}/versions/{versionId}/submit-review")
    public DocumentDtos.DocumentDetail submitReview(
            Authentication authentication,
            @PathVariable UUID documentId,
            @PathVariable UUID versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        UUID actorId = userId(authentication);
        return execute(
                actorId, KnowledgeWriteAuthorization.Action.SUBMIT_FOR_REVIEW,
                "document.submit-review", idempotencyKey,
                new MutationInput(documentId, versionId, null),
                () -> documents.submitForReview(documentId, versionId, actorId, clock.instant()));
    }

    @PostMapping("/api/v1/documents/{documentId}/versions/{versionId}/reject")
    public DocumentDtos.DocumentDetail reject(
            Authentication authentication,
            @PathVariable UUID documentId,
            @PathVariable UUID versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DocumentDtos.RejectRequest request) {
        UUID actorId = userId(authentication);
        var validated = requireRequest(request);
        return execute(
                actorId, KnowledgeWriteAuthorization.Action.REVIEW_DOCUMENT,
                "document.reject", idempotencyKey,
                new MutationInput(documentId, versionId, validated),
                () -> documents.reject(documentId, versionId, actorId, validated.comment(), clock.instant()));
    }

    @PostMapping("/api/v1/documents/{documentId}/versions/{versionId}/publish")
    public DocumentDtos.DocumentDetail publish(
            Authentication authentication,
            @PathVariable UUID documentId,
            @PathVariable UUID versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        UUID actorId = userId(authentication);
        return execute(
                actorId, KnowledgeWriteAuthorization.Action.PUBLISH_DOCUMENT,
                "document.publish", idempotencyKey,
                new MutationInput(documentId, versionId, null),
                () -> documents.publish(documentId, versionId, actorId, clock.instant()));
    }

    @PostMapping("/api/v1/documents/{documentId}/archive")
    public DocumentDtos.DocumentDetail archive(
            Authentication authentication,
            @PathVariable UUID documentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        UUID actorId = userId(authentication);
        return execute(
                actorId, KnowledgeWriteAuthorization.Action.ARCHIVE_DOCUMENT,
                "document.archive", idempotencyKey,
                new MutationInput(documentId, null, null),
                () -> documents.archive(documentId, actorId, clock.instant()));
    }

    private DocumentDtos.DocumentDetail detail(UUID actorId, UUID documentId) {
        return detail(documents.getVisibleDocument(actorId, documentId, clock.instant()));
    }

    private DocumentDtos.DocumentDetail detail(com.yunjihai.rag.knowledge.DocumentView view) {
        return DocumentDtos.DocumentDetail.from(view, parseErrors);
    }

    private DocumentDtos.DocumentDetail execute(
            UUID actorId,
            KnowledgeWriteAuthorization.Action requiredAction,
            String operation,
            String idempotencyKey,
            MutationInput request,
            java.util.function.Supplier<DocumentAggregate> action) {
        documents.requireAction(actorId, request.documentId(), requiredAction);
        var result = idempotency.execute(
                new IdempotencyScope(actorId, operation, idempotencyKey),
                IdempotencyService.sha256(json.writeValueAsBytes(request)),
                MutationResult.class,
                () -> {
                    var updated = action.get();
                    return new MutationResult(updated.id(), updated.revision());
                });
        return detail(actorId, result.documentId());
    }

    private static ResponseEntity<byte[]> contentResponse(DocumentContent content, boolean attachment) {
        String filename = safeDownloadFilename(content.filename());
        var disposition = attachment
                ? ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build()
                : ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(content.mediaType()));
        headers.setContentDisposition(disposition);
        return ResponseEntity.ok().headers(headers).body(content.bytes());
    }

    private static String safeDownloadFilename(String original) {
        if (original == null) return "download.bin";
        String basename = original.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1);
        int control = firstControl(basename);
        if (control >= 0) {
            int dot = basename.lastIndexOf('.');
            String extension = dot > control && dot < basename.length() - 1
                    ? basename.substring(dot).replaceAll("[^A-Za-z0-9.]", "")
                    : "";
            basename = basename.substring(0, control) + extension;
        }
        basename = basename.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_")
                .replaceFirst("^[. ]+", "")
                .strip();
        if (basename.isEmpty() || basename.equals(".") || basename.equals("..")) {
            return "download.bin";
        }
        return basename.length() <= 255 ? basename : basename.substring(0, 255);
    }

    private static int firstControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return index;
        }
        return -1;
    }

    private static <T> T requireRequest(T request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        return request;
    }

    private static List<String> requireTags(List<String> tags) {
        if (tags == null) throw new IllegalArgumentException("tags are required");
        return tags;
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AccessDeniedAsNotFoundException();
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            throw new AccessDeniedAsNotFoundException();
        }
    }

    private record MutationInput(UUID documentId, UUID versionId, Object body) { }

    private record MutationResult(UUID documentId, long revision) { }
}
