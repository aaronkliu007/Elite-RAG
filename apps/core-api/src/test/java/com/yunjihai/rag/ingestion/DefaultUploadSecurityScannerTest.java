package com.yunjihai.rag.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultUploadSecurityScannerTest {

    @Test
    void detectsPdfEncryptionFromRealBytes() throws Exception {
        var file = Files.createTempFile("encrypted-", ".pdf");
        try {
            Files.writeString(file, "%PDF-1.7\n1 0 obj << /Encrypt 2 0 R >>", StandardCharsets.ISO_8859_1);
            var scanner = new DefaultUploadSecurityScanner(UploadPolicy.defaults(), path -> false);

            var inspection = scanner.inspect(file, "secret.pdf", MediaType.APPLICATION_PDF);

            assertThat(inspection.detectedMediaType()).isEqualTo(MediaType.APPLICATION_PDF);
            assertThat(inspection.encrypted()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void detectsOoxmlArchiveBombFromExpandedBytes() throws Exception {
        var file = Files.createTempFile("bomb-", ".docx");
        try {
            Files.write(file, docxWithDocument(new byte[2048]));
            var scanner = new DefaultUploadSecurityScanner(
                    new UploadPolicy(10_000, 10, 1024, 1_000), path -> false);

            var inspection = scanner.inspect(file, "bomb.docx", docxMediaType());

            assertThat(inspection.detectedMediaType()).isEqualTo(docxMediaType());
            assertThat(inspection.archiveBomb()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void invokesMalwareScannerAndReportsVerdict() throws Exception {
        var file = Files.createTempFile("image-", ".png");
        try {
            Files.write(file, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            var scanner = new DefaultUploadSecurityScanner(UploadPolicy.defaults(), path -> true);

            var inspection = scanner.inspect(file, "evil.png", MediaType.IMAGE_PNG);

            assertThat(inspection.detectedMediaType()).isEqualTo(MediaType.IMAGE_PNG);
            assertThat(inspection.malicious()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static byte[] docxWithDocument(byte[] content) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("types".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(content);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static MediaType docxMediaType() {
        return MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }
}
