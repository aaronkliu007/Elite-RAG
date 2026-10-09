package com.yunjihai.rag.knowledge.web;

import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.api.ApiExceptionHandler;
import com.yunjihai.rag.ingestion.DocumentVersionId;
import com.yunjihai.rag.ingestion.UploadCommand;
import com.yunjihai.rag.ingestion.UploadPolicy;
import com.yunjihai.rag.ingestion.UploadRejectedException;
import com.yunjihai.rag.knowledge.DocumentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadControllerTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-7000-8000-000000000901");
    private static final UUID SPACE = UUID.fromString("00000000-0000-7000-8000-000000000902");
    private static final UUID DOCUMENT = UUID.fromString("00000000-0000-7000-8000-000000000903");
    private static final UUID VERSION = UUID.fromString("00000000-0000-7000-8000-000000000904");

    @ParameterizedTest
    @MethodSource("supportedFiles")
    void streamsAllSixSupportedFileTypes(String filename, String mediaType) throws Exception {
        var captured = new AtomicReference<UploadCommand>();
        var controller = new UploadController((command, key) -> {
            captured.set(command);
            assertThat(key).isEqualTo("upload-key");
            return new DocumentVersionId(DOCUMENT, VERSION);
        });
        var file = new MockMultipartFile("file", filename, mediaType, new byte[] {1, 2, 3});

        var accepted = controller.upload(authentication(ACTOR), SPACE, "制度", "upload-key", file);

        assertThat(accepted.documentId()).isEqualTo(DOCUMENT);
        assertThat(accepted.versionId()).isEqualTo(VERSION);
        assertThat(accepted.status()).isEqualTo("DRAFT");
        assertThat(captured.get())
                .extracting(UploadCommand::spaceId, UploadCommand::title,
                        UploadCommand::originalFilename, UploadCommand::declaredLength, UploadCommand::actorId)
                .containsExactly(SPACE, "制度", filename, 3L, ACTOR);
        assertThat(captured.get().declaredMediaType().toString()).isEqualTo(mediaType);
        assertThat(captured.get().stream().readAllBytes()).containsExactly(1, 2, 3);
    }

    @Test
    void missingIdempotencyKeyIsAStableBadRequest() throws Exception {
        var controller = new UploadController((command, key) -> new DocumentVersionId(DOCUMENT, VERSION));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(multipart("/api/v1/knowledge-spaces/{spaceId}/documents", SPACE)
                        .file(new MockMultipartFile("file", "policy.pdf", "application/pdf", new byte[] {1}))
                        .param("title", "制度")
                        .principal(authentication(ACTOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void rejectsEmptyOversizedForgedAndEncryptedUploadsBeforeAcceptance() {
        UploadPolicy policy = UploadPolicy.defaults();
        var controller = new UploadController((command, key) -> {
            policy.validateMetadata(command);
            if (command.originalFilename().startsWith("encrypted")) {
                throw new UploadRejectedException("暂不接受加密文档");
            }
            return new DocumentVersionId(DOCUMENT, VERSION);
        });

        assertThatThrownBy(() -> controller.upload(authentication(ACTOR), SPACE, "空文件", "k1",
                new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(UploadRejectedException.class);
        assertThatThrownBy(() -> controller.upload(authentication(ACTOR), SPACE, "超大", "k2",
                sizedFile("huge.pdf", "application/pdf", 100L * 1024 * 1024 + 1)))
                .isInstanceOf(UploadRejectedException.class);
        assertThatThrownBy(() -> controller.upload(authentication(ACTOR), SPACE, "伪造", "k3",
                new MockMultipartFile("file", "forged.pdf", "image/png", new byte[] {1})))
                .isInstanceOf(UploadRejectedException.class);
        assertThatThrownBy(() -> controller.upload(authentication(ACTOR), SPACE, "加密", "k4",
                new MockMultipartFile("file", "encrypted.pdf", "application/pdf", new byte[] {1})))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("加密");
    }

    @Test
    void preservesFailClosedAuthorizationFromUploadService() {
        var controller = new UploadController((command, key) -> {
            throw new AccessDeniedAsNotFoundException();
        });

        assertThatThrownBy(() -> controller.upload(authentication(ACTOR), SPACE, "只读越权", "key",
                new MockMultipartFile("file", "reader.md", "text/markdown", new byte[] {1})))
                .isExactlyInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test
    void closesMultipartStreamAfterSynchronousHandoff() throws Exception {
        var closed = new AtomicBoolean();
        MultipartFile file = trackingFile(closed);
        var controller = new UploadController((command, key) -> {
            assertThat(readAll(command.stream())).containsExactly(1, 2, 3);
            return new DocumentVersionId(DOCUMENT, VERSION);
        });

        controller.upload(authentication(ACTOR), SPACE, "制度", "key", file);

        assertThat(closed).isTrue();
    }

    @Test
    void passesClassificationAndTagsIntoTheIdempotentUploadCommand() throws Exception {
        var captured = new AtomicReference<UploadCommand>();
        var controller = new UploadController((command, key) -> {
            captured.set(command);
            return new DocumentVersionId(DOCUMENT, VERSION);
        });

        controller.upload(
                authentication(ACTOR), SPACE, "制度", "管理制度", List.of("质量", "流程"), "key",
                new MockMultipartFile("file", "policy.pdf", "application/pdf", new byte[] {1}));

        assertThat(captured.get().classification()).isEqualTo("管理制度");
        assertThat(captured.get().tags()).containsExactly("质量", "流程");
    }

    @Test
    void reportsTheActualStatusWhenAnExistingVersionIsReused() throws Exception {
        var controller = new UploadController(
                (command, key) -> new DocumentVersionId(DOCUMENT, VERSION),
                version -> DocumentStatus.PUBLISHED);

        var accepted = controller.upload(
                authentication(ACTOR), SPACE, "制度", "key",
                new MockMultipartFile("file", "policy.pdf", "application/pdf", new byte[] {1}));

        assertThat(accepted.status()).isEqualTo("PUBLISHED");
    }

    private static Stream<Arguments> supportedFiles() {
        return Stream.of(
                Arguments.of("policy.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                Arguments.of("report.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                Arguments.of("manual.pdf", "application/pdf"),
                Arguments.of("proposal.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                Arguments.of("readme.md", "text/markdown"),
                Arguments.of("diagram.png", "image/png"));
    }

    private static UsernamePasswordAuthenticationToken authentication(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), "n/a", List.of());
    }

    private static MultipartFile sizedFile(String filename, String contentType, long size) {
        return new MultipartFile() {
            public String getName() { return "file"; }
            public String getOriginalFilename() { return filename; }
            public String getContentType() { return contentType; }
            public boolean isEmpty() { return false; }
            public long getSize() { return size; }
            public byte[] getBytes() { return new byte[] {1}; }
            public InputStream getInputStream() { return new ByteArrayInputStream(new byte[] {1}); }
            public void transferTo(java.io.File dest) throws IOException { throw new UnsupportedOperationException(); }
        };
    }

    private static MultipartFile trackingFile(AtomicBoolean closed) {
        return new MultipartFile() {
            public String getName() { return "file"; }
            public String getOriginalFilename() { return "policy.pdf"; }
            public String getContentType() { return "application/pdf"; }
            public boolean isEmpty() { return false; }
            public long getSize() { return 3; }
            public byte[] getBytes() { return new byte[] {1, 2, 3}; }
            public InputStream getInputStream() {
                return new ByteArrayInputStream(new byte[] {1, 2, 3}) {
                    @Override public void close() throws IOException { closed.set(true); super.close(); }
                };
            }
            public void transferTo(java.io.File dest) { throw new UnsupportedOperationException(); }
        };
    }

    private static byte[] readAll(InputStream input) {
        try {
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
