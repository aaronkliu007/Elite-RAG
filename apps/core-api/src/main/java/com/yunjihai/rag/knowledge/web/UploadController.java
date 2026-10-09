package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.ingestion.DocumentVersionId;
import com.yunjihai.rag.ingestion.UploadCommand;
import com.yunjihai.rag.ingestion.UploadService;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.DocumentVersionStatusQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@Profile("local")
public class UploadController {
    private final UploadHandler uploads;
    private final DocumentVersionStatusQuery statuses;

    @Autowired
    public UploadController(
            @Qualifier("runtimeUploadService") UploadService uploads,
            DocumentVersionStatusQuery statuses) {
        this(uploads::accept, statuses);
    }

    UploadController(UploadHandler uploads) {
        this(uploads, ignored -> DocumentStatus.DRAFT);
    }

    UploadController(UploadHandler uploads, DocumentVersionStatusQuery statuses) {
        this.uploads = Objects.requireNonNull(uploads);
        this.statuses = Objects.requireNonNull(statuses);
    }

    @PostMapping(
            path = "/api/v1/knowledge-spaces/{spaceId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentDtos.UploadAccepted upload(
            Authentication authentication,
            @PathVariable UUID spaceId,
            @RequestParam String title,
            @RequestParam(required = false) String classification,
            @RequestParam(required = false) List<String> tags,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam("file") MultipartFile file) throws IOException {
        Objects.requireNonNull(file, "file is required");
        String filename = safeUploadFilename(file.getOriginalFilename());
        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("file content type is required");
        }
        try (var stream = file.getInputStream()) {
            var command = new UploadCommand(
                    spaceId,
                    title,
                    classification,
                    tags == null ? List.of() : tags,
                    filename,
                    MediaType.parseMediaType(contentType),
                    file.getSize(),
                    stream,
                    userId(authentication));
            DocumentVersionId accepted = uploads.accept(command, idempotencyKey);
            return new DocumentDtos.UploadAccepted(
                    accepted.documentId(), accepted.versionId(), statuses.find(accepted).name());
        }
    }

    DocumentDtos.UploadAccepted upload(
            Authentication authentication,
            UUID spaceId,
            String title,
            String idempotencyKey,
            MultipartFile file) throws IOException {
        return upload(authentication, spaceId, title, null, List.of(), idempotencyKey, file);
    }

    private static String safeUploadFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255
                || filename.contains("/") || filename.contains("\\")
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("file name is invalid");
        }
        return filename;
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

    @FunctionalInterface
    interface UploadHandler {
        DocumentVersionId accept(UploadCommand command, String idempotencyKey);
    }
}
